"""Compare the streaming merge formula with actual PEFT safe_merge on real tensors (CPU)."""
import argparse
from common import *

def main():
    p=argparse.ArgumentParser();p.add_argument('--experiment',choices=['A','B'],required=True);a=p.parse_args();offline()
    import torch
    from safetensors import safe_open
    from safetensors.torch import load_file
    from peft.tuners.lora import Linear
    from peft import LoraConfig
    run=OUT/a.experiment;base=BASES/'Qwen3.5-4B'
    config=json.loads((run/'adapter/adapter_config.json').read_text());weights=load_file(str(run/'adapter/adapter_model.safetensors'))
    index=json.loads((base/'model.safetensors.index.json').read_text())['weight_map'];torch.set_num_threads(4)
    chosen=[]
    for ending in ('.linear_attn.in_proj_qkv.lora_A.weight','.self_attn.q_proj.lora_A.weight','.mlp.gate_proj.lora_A.weight'):
        chosen.append(next(k for k in weights if k.endswith(ending)))
    results=[]
    for key in chosen:
        canonical=key.removeprefix('base_model.model.').replace('.lora_A.weight','.weight')
        with safe_open(str(base/index[canonical]),framework='pt',device='cpu') as source:tensor=source.get_tensor(canonical).clone()
        left=weights[key];right=weights[key.replace('.lora_A.weight','.lora_B.weight')]
        delta=(config['lora_alpha']/config['r']*(right.float()@left.float())).to(tensor.dtype)
        expected=(tensor+delta).to(torch.bfloat16)
        layer=torch.nn.Linear(tensor.shape[1],tensor.shape[0],bias=False,dtype=torch.bfloat16)
        layer.weight.data=tensor
        wrapped=Linear(layer,'default',LoraConfig(r=config['r'],lora_alpha=config['lora_alpha'],lora_dropout=0.),r=config['r'],lora_alpha=config['lora_alpha'])
        wrapped.lora_A['default'].float();wrapped.lora_B['default'].float()
        with torch.no_grad():
            wrapped.lora_A['default'].weight.copy_(left);wrapped.lora_B['default'].weight.copy_(right)
        wrapped.merge(safe_merge=True)
        actual=wrapped.base_layer.weight.detach()
        difference=float((actual.float()-expected.float()).abs().max())
        results.append({'parameter':canonical,'shape':list(tensor.shape),'bitwise_equal':torch.equal(actual,expected),'max_absolute_difference':difference})
        del wrapped,layer,tensor,actual,expected
    report={'experiment':a.experiment,'sampled_targets':results,'all_passed':all(r['bitwise_equal'] for r in results),
        'adapter_sha256':digest(run/'adapter/adapter_model.safetensors'),'scope':'Three actual projection families; not an exhaustive all-tensor test','cuda_used':False}
    write_json(run/'merge-conformance.json',report)
    if not report['all_passed']:raise ValueError('Streaming formula differs from PEFT safe_merge')
    print(json.dumps(report),flush=True)

if __name__=='__main__':main()
