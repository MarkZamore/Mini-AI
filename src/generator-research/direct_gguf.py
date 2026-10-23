"""Export learned matrices with exactly the deployed text-only Q6 tensor policy.

Unmodified tensors retain ORIGINAL foundation dtypes/values. Their quantized bytes
are checked against the deployed baseline, preventing export changes masquerading
as a training effect. The previously created standard/MTP export is not used.
"""
import argparse, collections, logging, sys
from common import *

def main():
    p=argparse.ArgumentParser();p.add_argument('--experiment',choices=['A','B'],required=True)
    p.add_argument('--finalize-existing',action='store_true',help='Recheck an already written partial GGUF after a finalization-only interruption')
    a=p.parse_args();offline()
    import torch
    import psutil
    from safetensors.torch import load_file
    if psutil.virtual_memory().available<6*1024**3:raise MemoryError('Insufficient RAM reserve')
    run=OUT/a.experiment;base=BASES/'Qwen3.5-4B';adapter=run/'adapter'
    if json.loads((run/'training-report.json').read_text())['status']!='one_epoch_complete':raise ValueError('Unfinished training')
    config=json.loads((adapter/'adapter_config.json').read_text());weights=load_file(str(adapter/'adapter_model.safetensors'))
    if pathlib.Path(config['base_model_name_or_path']).resolve()!=base.resolve():raise ValueError('Wrong foundation')
    pairs={}
    for key,value in weights.items():
        if key.endswith('.lora_A.weight'):
            # ModelBase.get_tensors normalizes away language_model. before
            # calling model-specific conversion. Match that canonical namespace.
            canonical=key.removeprefix('base_model.model.').replace('.lora_A.weight','.weight').replace('language_model.','')
            pairs[canonical]=(value,weights[key.replace('.lora_A.weight','.lora_B.weight')])
    if len(pairs)*2!=len(weights):raise ValueError('Unexpected adapter entries')
    sys.path.insert(0,str(ROOT/'src/build/model-preparation/llama.cpp'))
    sys.path.insert(0,str(ROOT/'src/build/model-preparation'))
    import gguf,gguf.quants
    from conversion import ModelBase,ModelType,get_model_architecture,get_model_class
    from conversion.base import LazyTorchTensor
    from native_quant import Quantizer,EXE
    torch.set_num_threads(4);logging.basicConfig(level=logging.WARNING)
    hparams=ModelBase.load_hparams(base,False);parent=get_model_class(get_model_architecture(hparams,ModelType.TEXT),mmproj=False)
    updated_names=set();used=set();scale=config['lora_alpha']/config['r']
    class Export(parent):
        model_arch=gguf.MODEL_ARCH.QWEN35
        no_mtp=True
        def __init__(self,*args,**kwargs):self.shapes={};super().__init__(*args,**kwargs)
        def get_tensors(self):
            for name,tensor in super().get_tensors():
                if name in pairs:
                    def apply(value,name=name):
                        left,right=pairs[name];used.add(name)
                        delta=(scale*(right.float()@left.float())).to(value.dtype)
                        return value+delta
                    tensor=LazyTorchTensor._wrap_fn(apply,meta_noop=True)(tensor)
                yield name,tensor
        def modify_tensors(self,tensor,name,bid):
            for converted,value in super().modify_tensors(tensor,name,bid):
                self.shapes[converted]=tuple(value.shape)
                if name in pairs:updated_names.add(converted)
                yield converted,value
        def tensor_force_quant(self,name,converted,bid,n_dims):
            shape=self.shapes[converted]
            if n_dims<=1 or converted.endswith('_norm.weight') or 'conv1d' in converted or not converted.endswith('.weight'):return gguf.GGMLQuantizationType.F32
            if n_dims==2 and shape[-1]%256==0:return gguf.GGMLQuantizationType.Q6_K
            return gguf.GGMLQuantizationType.F16
    output=run/'matched-baseline-Q6_K.gguf';temporary=run/'matched-baseline-Q6_K.partial.gguf'
    if output.exists() or (temporary.exists() and not a.finalize_existing):raise FileExistsError('Preserve previous matched export')
    if a.finalize_existing:
        if not temporary.exists():raise FileNotFoundError(temporary)
    else:
        with Quantizer() as encoder:
            gguf.quants.Q6_K.quantize_blocks=classmethod(lambda cls,blocks:encoder.quantize(blocks))
            model=Export(base,gguf.LlamaFileType.MOSTLY_Q6_K,temporary,eager=False,use_temp_file=False,hparams=hparams)
            model.write()
        if used!=set(pairs):raise ValueError('Some learned matrices were not exported')
    deployed=ROOT/'model/weights/Qwen3.5-4B-baseline-Q6_K.gguf'
    old=gguf.GGUFReader(deployed);new=gguf.GGUFReader(temporary)
    if a.finalize_existing:
        # For this fixed Qwen3.5-4B layout, every quantized body matrix has a
        # LoRA pair; token embeddings are the only frozen Q6 matrix.
        updated_names={t.name for t in new.tensors if t.tensor_type.name=='Q6_K' and t.name!='token_embd.weight'}
        if len(updated_names)!=len(pairs) or any(not name.startswith('blk.') for name in updated_names):
            raise ValueError('Unexpected layout for finalization-only verification')
    old_tensors={t.name:t for t in old.tensors};new_tensors={t.name:t for t in new.tensors}
    if old_tensors.keys()!=new_tensors.keys():raise ValueError('Deployment tensor inventory differs')
    unchanged=0;changed=0;differences=[]
    import numpy as np
    for name,current in new_tensors.items():
        prior=old_tensors[name]
        if current.tensor_type!=prior.tensor_type or list(current.shape)!=list(prior.shape):raise ValueError('Tensor type/shape differs: '+name)
        equal=np.array_equal(current.data,prior.data)
        if name not in updated_names:
            if not equal:differences.append(name)
            unchanged+=1
        elif not equal:changed+=1
    report={'experiment':a.experiment,'tensor_count':len(new_tensors),'precision_counts':dict(collections.Counter(t.tensor_type.name for t in new.tensors)),
        'mapped_updated_tensors':len(updated_names),'changed_quantized_tensors':changed,'unmodified_tensors_checked':unchanged,
        'unmodified_tensor_differences':differences,'identical_deployed_inventory_types_shapes':True,
        'adapter_sha256':digest(adapter/'adapter_model.safetensors'),'native_encoder_sha256':digest(EXE),'export_source_sha256':digest(__file__),
        'finalization_only_resume':a.finalize_existing,
        'policy':'Original foundation dtypes outside learned matrices; PEFT-compatible merge; no MTP/vision; deployed Q6 tensor policy',
        'production_installed':False,'quality_acceptance':False}
    if differences:
        write_json(run/'matched-export-failed.json',report);raise ValueError('Unmodified tensors differ from deployed baseline: '+repr(differences))
    # A loop variable also owns its numpy memmap; Windows cannot rename while it
    # survives even after the reader dictionaries themselves have been released.
    del old,new,old_tensors,new_tensors,current,prior
    import gc
    gc.collect()
    os.replace(temporary,output);report.update({'path':str(output),'sha256':digest(output),'bytes':output.stat().st_size})
    write_json(run/'matched-gguf-export-report.json',report);print(json.dumps(report),flush=True)

if __name__=='__main__':main()
