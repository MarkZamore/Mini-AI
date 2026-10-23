"""Repack already merged/quantized learned matrices into the exact baseline container.

Only trained projection matrices are replaced. Every source Q6 matrix is sampled
against the pinned native block encoder using its saved F16 input; original
metadata, token embeddings, norms and other frozen tensors remain unchanged.
"""
import argparse, gc, shutil, sys
from common import *

def main():
    p=argparse.ArgumentParser();p.add_argument('--experiment',choices=['A','B'],required=True);a=p.parse_args();offline()
    sys.path.insert(0,str(ROOT/'src/build/model-preparation/llama.cpp/gguf-py'))
    sys.path.insert(0,str(ROOT/'src/build/model-preparation'))
    import gguf,numpy as np
    from native_quant import Quantizer,EXE
    run=OUT/a.experiment
    merge=json.loads((run/'merge-report.json').read_text())
    if merge['updated_tensors']!=248:raise ValueError('Unexpected learned-matrix count')
    baseline=ROOT/'model/weights/Qwen3.5-4B-baseline-Q6_K.gguf'
    source_path=run/'merged-text-Q6_K.gguf';f16_path=run/'merged-text-F16.gguf'
    source_report=json.loads((run/'gguf-export-report.json').read_text())
    if digest(source_path)!=source_report['sha256']:raise ValueError('Source quantized export changed')
    output=run/'matched-baseline-Q6_K.gguf'
    if output.exists():raise FileExistsError('Preserve existing matched export')
    shutil.copyfile(baseline,output)
    def patch_and_check():
        original=gguf.GGUFReader(baseline);source=gguf.GGUFReader(source_path);f16=gguf.GGUFReader(f16_path);destination=gguf.GGUFReader(output,mode='r+')
        old={t.name:t for t in original.tensors};new={t.name:t for t in destination.tensors};quantized={t.name:t for t in source.tensors};full={t.name:t for t in f16.tensors}
        trained={name for name,t in old.items() if t.tensor_type.name=='Q6_K' and name!='token_embd.weight'}
        if len(trained)!=248 or old.keys()!=new.keys():raise ValueError('Unexpected baseline projection layout')
        sampled=0
        with Quantizer() as encoder:
            for name in sorted(trained):
                reference=old[name];candidate=quantized[name];values=full[name]
                if candidate.tensor_type!=reference.tensor_type or list(candidate.shape)!=list(reference.shape):raise ValueError('Projection shape/type mismatch: '+name)
                if values.tensor_type.name not in ('F16','F32'):raise ValueError('Expected saved full-precision quantizer input')
                blocks=values.data.reshape(-1,256);encoded=candidate.data.reshape(-1,210)
                indices=[0,len(blocks)//2,len(blocks)-1]
                actual=encoder.quantize(np.asarray(blocks[indices],dtype=np.float32))
                if not np.array_equal(actual,encoded[indices]):raise ValueError('Standard and pinned native Q6 encoders differ: '+name)
                sampled+=len(indices)
                new[name].data[:]=candidate.data
        destination.data.flush()
        changed=[];frozen=[]
        for name,tensor in new.items():
            equal=np.array_equal(tensor.data,old[name].data)
            if name not in trained:
                if not equal:raise ValueError('Frozen tensor changed: '+name)
                frozen.append(name)
            elif not equal:changed.append(name)
        if not changed:raise ValueError('No learned quantized matrices changed')
        return {'tensor_count':len(new),'mapped_updated_tensors':len(trained),'changed_quantized_tensors':len(changed),
            'unmodified_tensors_checked':len(frozen),'unmodified_tensor_differences':[],'identical_deployed_inventory_types_shapes':True,
            'metadata_and_tokenizer':'Exact baseline file container copied without modifying its header',
            'pinned_native_encoder_blocks_sampled':sampled,'all_sampled_encoder_blocks_bitwise_equal':True}
    report=patch_and_check();gc.collect()
    report.update({'experiment':a.experiment,'path':str(output),'sha256':digest(output),'bytes':output.stat().st_size,
        'adapter_sha256':digest(run/'adapter/adapter_model.safetensors'),'baseline_sha256':digest(baseline),
        'merged_source_sha256':digest(f16_path),'quantized_source_sha256':digest(source_path),'native_encoder_sha256':digest(EXE),
        'source_code_sha256':digest(__file__),'production_installed':False,'quality_acceptance':False,
        'scope':'Only learned Q6 projection matrices replaced; all frozen tensors and exact header preserved; 3 blocks per projection match native encoder'})
    write_json(run/'matched-gguf-export-report.json',report);print(json.dumps(report),flush=True)

if __name__=='__main__':main()
