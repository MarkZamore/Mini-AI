"""Streaming BF16 merge into bounded shards, then optional text GGUF/Q6 export.

Outputs are research candidates, never automatically copied into root/model.
The original foundation and old adapters remain untouched.
"""
from __future__ import annotations
import argparse, shutil, subprocess, sys
from common import *

def main():
    p=argparse.ArgumentParser();p.add_argument('--experiment',choices=['A','B','partial08'],required=True)
    p.add_argument('--gguf',action='store_true');p.add_argument('--shard-mib',type=int,default=512)
    a=p.parse_args();offline()
    import torch
    import psutil
    from safetensors import safe_open
    from safetensors.torch import load_file,save_file
    run=OUT/a.experiment;report=json.loads((run/'training-report.json').read_text())
    if report['status']!='one_epoch_complete':raise ValueError('Only complete one-epoch research checkpoints are exportable')
    name='Qwen3.5-0.8B' if a.experiment=='partial08' else 'Qwen3.5-4B';base=BASES/name
    foundation=foundation_lock(name)
    if foundation!=json.loads((run/'foundation-lock.json').read_text()):raise ValueError('Foundation changed')
    index=json.loads((base/'model.safetensors.index.json').read_text());weight_map=index['weight_map']
    base_bytes=sum((base/f).stat().st_size for f in set(weight_map.values()))
    required=base_bytes*(2.5 if a.gguf else 1.1)+20*1024**3
    if shutil.disk_usage(run).free<required:raise MemoryError('Insufficient disk for merge/export plus 20 GiB reserve; no original files are deleted')
    if psutil.virtual_memory().available<8*1024**3:raise MemoryError('Wait for at least 8 GiB available host RAM')
    merged=run/'merged-bf16'
    if merged.exists():raise FileExistsError('Preserve existing merge; inspect it before choosing a new output directory')
    merged.mkdir();pairs={};replacements={};scale=1.
    if a.experiment=='partial08':replacements=load_file(str(run/'partial-base-weights.safetensors'),device='cpu')
    else:
        config=json.loads((run/'adapter/adapter_config.json').read_text());scale=config['lora_alpha']/config['r']
        if pathlib.Path(config['base_model_name_or_path']).resolve()!=base.resolve():raise ValueError('Adapter belongs to a different foundation')
        if config['r']!=8 or config.get('use_dora') or config.get('modules_to_save'):raise ValueError('Unexpected adapter layout')
        tensors=load_file(str(run/'adapter/adapter_model.safetensors'),device='cpu')
        for key,value in tensors.items():
            if '.lora_A.weight' not in key:continue
            other=key.replace('.lora_A.weight','.lora_B.weight')
            if other not in tensors:raise ValueError('Missing LoRA B tensor')
            canonical=key.removeprefix('base_model.model.').replace('.lora_A.weight','.weight')
            if canonical not in weight_map:raise ValueError('Unknown base merge target: '+canonical)
            pairs[canonical]=(value,tensors[other])
        if len(pairs)*2!=len(tensors):raise ValueError('Unrecognized adapter tensors')
    output_map={};buffer={};size=0;shard=0;total=0;changed=set()
    def flush():
        nonlocal buffer,size,shard
        if not buffer:return
        shard+=1;filename=f'model-research-{shard:05d}.safetensors'
        save_file(buffer,str(merged/filename),metadata={'format':'pt'})
        for key in buffer:output_map[key]=filename
        buffer={};size=0
    torch.set_num_threads(4)
    for filename in sorted(set(weight_map.values())):
        with safe_open(str(base/filename),framework='pt',device='cpu') as source:
            for key in source.keys():
                tensor=source.get_tensor(key)
                if key in pairs:
                    left,right=pairs[key]
                    # PEFT 0.21 safe_merge rounds the delta to the base dtype before
                    # adding it. Match that behavior rather than introducing a new merge.
                    delta=(scale*(right.float()@left.float())).to(tensor.dtype)
                    tensor=(tensor+delta).to(torch.bfloat16);changed.add(key)
                elif key in replacements:tensor=replacements[key].to(torch.bfloat16);changed.add(key)
                elif tensor.is_floating_point():tensor=tensor.to(torch.bfloat16)
                tensor=tensor.contiguous().clone();amount=tensor.numel()*tensor.element_size()
                if buffer and size+amount>a.shard_mib*1024**2:flush()
                buffer[key]=tensor;size+=amount;total+=amount
                if size>=a.shard_mib*1024**2:flush()
                if psutil.virtual_memory().available<2*1024**3:raise MemoryError('Host RAM reserve breached; incomplete merge preserved')
    flush()
    if changed!=set(pairs or replacements):raise ValueError('Some learned tensors were not merged')
    for source in base.iterdir():
        if source.is_file() and source.suffix!='.safetensors' and source.name!='model.safetensors.index.json':shutil.copy2(source,merged/source.name)
    write_json(merged/'model.safetensors.index.json',{'metadata':{'total_size':total},'weight_map':output_map})
    evidence={'experiment':a.experiment,'foundation_revision':foundation['revision'],'merged_tensors':len(output_map),'updated_tensors':len(changed),
        'precision':'BF16 merged weights; FP32 LoRA products rounded to base dtype before PEFT-compatible addition','quality_acceptance':False,'production_installed':False,
        'files':[{'path':p.name,'sha256':digest(p),'bytes':p.stat().st_size} for p in sorted(merged.glob('*.safetensors'))]}
    write_json(run/'merge-report.json',evidence)
    if a.gguf:
        if a.experiment in ('A','B'):
            subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('direct_gguf.py')),'--experiment',a.experiment],check=True)
            print(json.dumps(evidence),flush=True)
            return
        converter=ROOT/'src/build/model-preparation/llama.cpp/convert_hf_to_gguf.py'
        quantizer=ROOT/'src/build/model-preparation/native-build/Release/prepare-quantize.exe'
        f16=run/'merged-text-F16.gguf';q6=run/'merged-text-Q6_K.gguf'
        with (run/'gguf-conversion.log').open('w',encoding='utf-8') as output:
            subprocess.run([sys.executable,str(converter),str(merged),'--outfile',str(f16),'--outtype','f16','--no-mtp'],check=True,stdout=output,stderr=subprocess.STDOUT)
        with (run/'q6-quantization.log').open('w',encoding='utf-8') as output:
            subprocess.run([str(quantizer),str(f16.resolve()),str(q6.resolve()),'Q6_K'],check=True,stdout=output,stderr=subprocess.STDOUT)
        write_json(run/'gguf-export-report.json',{'path':str(q6),'sha256':digest(q6),'bytes':q6.stat().st_size,
            'converter_sha256':digest(converter),'quantizer_sha256':digest(quantizer),'merge_report_sha256':digest(run/'merge-report.json'),
            'scope':'Research candidate; postquantization evaluation and package acceptance still required','production_installed':False})
    print(json.dumps(evidence),flush=True)

if __name__=='__main__':main()
