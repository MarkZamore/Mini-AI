"""CPU round-trip check of a real saved optimizer/checkpoint; no CUDA initialization."""
import argparse
from common import *

def main():
    p=argparse.ArgumentParser();p.add_argument('--experiment',choices=['A','B'],required=True);a=p.parse_args()
    import torch,numpy as np
    run=OUT/a.experiment;state=torch.load(run/'checkpoint.pt',map_location='cpu',weights_only=False)
    if torch.cuda.is_initialized():raise RuntimeError('CPU verification unexpectedly initialized CUDA')
    params=[torch.nn.Parameter(value.clone()) for value in state['trainable'].values()]
    optimizer=torch.optim.AdamW(params,lr=state['config']['learning_rate'],weight_decay=.01)
    optimizer.load_state_dict(state['optimizer'])
    actual=optimizer.state_dict();checks=0
    for key,original in state['optimizer']['state'].items():
        restored=actual['state'][key]
        for item,value in original.items():
            if isinstance(value,torch.Tensor):
                assert torch.equal(value,restored[item]),'Optimizer tensor changed';checks+=1
            else:assert value==restored[item]
    assert actual['param_groups']==state['optimizer']['param_groups']
    def restore():
        random.setstate(state['rng']['python']);np.random.set_state(state['rng']['numpy']);torch.set_rng_state(state['rng']['torch'])
    restore();one=(random.random(),float(np.random.rand()),torch.rand(4))
    restore();two=(random.random(),float(np.random.rand()),torch.rand(4))
    assert one[:2]==two[:2] and torch.equal(one[2],two[2])
    assert state['rng']['cuda'] and all(s.dtype==torch.uint8 and s.numel()>0 for s in state['rng']['cuda'])
    report={'checkpoint_sha256':digest(run/'checkpoint.pt'),'optimizer_tensors_bitwise_restored':checks,
        'python_numpy_torch_cpu_rng_replay_identical':True,'cuda_rng_bytes_present':True,'cuda_initialized':torch.cuda.is_initialized(),
        'step':state['step'],'cursor':state['cursor'],'scheduler_present':bool(state['scheduler']),'grad_scaler':state['grad_scaler'],
        'scope':'Actual saved checkpoint/AdamW tensors and CPU RNG restored. Interrupted CUDA next-step equivalence was not exercised.'}
    write_json(run/'resume-roundtrip-verification.json',report);print(json.dumps(report),flush=True)

if __name__=='__main__':main()
