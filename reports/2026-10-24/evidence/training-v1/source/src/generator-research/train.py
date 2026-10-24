"""CUDA generator pilots and one-epoch experiments with exact optimizer/RNG resume.

All GPU activity is protected by the shared atomic lease. Evaluation uses fixed
oracle source contexts and automatic literal atoms; it is not end-to-end RAG gold.
"""
from __future__ import annotations
import argparse, importlib.metadata, math, re, shutil, sys, traceback
from common import *

TARGETS=r'.*language_model\.layers\.\d+\.(?:self_attn\.(?:q_proj|k_proj|v_proj|o_proj)|linear_attn\.(?:in_proj_qkv|in_proj_z|in_proj_b|in_proj_a|out_proj)|mlp\.(?:gate_proj|up_proj|down_proj))$'

def rng_state(torch, np):
    return {'python':random.getstate(),'numpy':np.random.get_state(),'torch':torch.get_rng_state(),'cuda':torch.cuda.get_rng_state_all()}

def restore_rng(torch, np, state):
    random.setstate(state['python']);np.random.set_state(state['numpy']);torch.set_rng_state(state['torch'].cpu())
    torch.cuda.set_rng_state_all([s.cpu() for s in state['cuda']])

def trainable_state(model):
    return {name:p.detach().cpu().clone() for name,p in model.named_parameters() if p.requires_grad}

def fingerprint(torch, tensors):
    result={}
    for name,tensor in tensors.items():
        data=tensor.detach().cpu().contiguous().view(torch.uint8).numpy().tobytes()
        result[name]=hashlib.sha256(data).hexdigest()
    return result

def atomic_checkpoint(torch, path, state):
    temporary=path.with_suffix('.partial');torch.save(state,temporary);os.replace(temporary,path)

def load_model(torch, name, mode):
    from transformers import BitsAndBytesConfig,Qwen3_5ForConditionalGeneration
    base=BASES/name
    kwargs={'local_files_only':True,'trust_remote_code':False,'dtype':torch.bfloat16,'device_map':{'':0},'attn_implementation':'sdpa'}
    if mode=='qlora':
        kwargs['quantization_config']=BitsAndBytesConfig(load_in_4bit=True,bnb_4bit_quant_type='nf4',bnb_4bit_use_double_quant=True,bnb_4bit_compute_dtype=torch.bfloat16)
    model=Qwen3_5ForConditionalGeneration.from_pretrained(base,**kwargs)
    if mode=='qlora':
        from peft import LoraConfig,TaskType,get_peft_model,prepare_model_for_kbit_training
        model=prepare_model_for_kbit_training(model,use_gradient_checkpointing=True,gradient_checkpointing_kwargs={'use_reentrant':False})
        model=get_peft_model(model,LoraConfig(r=8,lora_alpha=16,lora_dropout=0.,target_modules=TARGETS,bias='none',task_type=TaskType.CAUSAL_LM))
        assert all('lora_' in n and 'language_model' in n and 'visual' not in n for n,p in model.named_parameters() if p.requires_grad)
    else:
        for p in model.parameters():p.requires_grad_(False)
        for block in model.model.language_model.layers[-4:]:
            block.to(dtype=torch.float32)
            for p in block.parameters():p.requires_grad_(True)
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.enable_input_require_grads()
        trainable=[n for n,p in model.named_parameters() if p.requires_grad]
        last=model.config.text_config.num_hidden_layers-4
        assert trainable and all(any(f'language_model.layers.{i}.' in n for i in range(last,last+4)) for n in trainable)
    model.config.use_cache=False
    assert all(p.device.type=='cuda' for p in model.parameters())
    return model

def loss_for(torch, model, row):
    length=len(row['input_ids']);prefix=row['prefix_length'];pad=row.get('pad_to',length)-length
    ids=torch.tensor([row['input_ids']+[248044]*pad],dtype=torch.long,device='cuda')
    mask=torch.tensor([[1]*length+[0]*pad],dtype=torch.long,device='cuda')
    positions=torch.arange(prefix-1,length-1,device='cuda')
    with torch.autocast('cuda',dtype=torch.bfloat16):
        output=model(input_ids=ids,attention_mask=mask,logits_to_keep=positions,use_cache=False)
        loss=torch.nn.functional.cross_entropy(output.logits.reshape(-1,output.logits.shape[-1]).float(),ids[:,prefix:length].reshape(-1),reduction='sum')
    return loss,row['target_tokens']

def evaluate(torch, model, tokenizer, rows, generation_ids, out, tag):
    out.mkdir(parents=True,exist_ok=True);model.eval();results=[];total_loss=0.;total_tokens=0
    with torch.inference_mode():
        for row in rows:
            loss,count=loss_for(torch,model,row);total_loss+=float(loss);total_tokens+=count
            item={'id':row['id'],'language':row['language'],'family_ids':row['family_ids'],'answer_tokens':count,'teacher_forced_nll':float(loss)/count}
            if row['id'] in generation_ids:
                prefix=torch.tensor([row['input_ids'][:row['prefix_length']]],device='cuda',dtype=torch.long)
                begun=time.monotonic()
                with torch.autocast('cuda',dtype=torch.bfloat16):
                    result=model.generate(input_ids=prefix,attention_mask=torch.ones_like(prefix),max_new_tokens=min(256,max(96,row['target_tokens']+48)),do_sample=False,use_cache=True,pad_token_id=tokenizer.eos_token_id)
                output=tokenizer.decode(result[0,prefix.shape[1]:],skip_special_tokens=True)
                normalized=lambda text:' '.join(re.findall(r'\w+',text.casefold()))
                atoms=[normalized(a['text']) for a in row['required_atoms']]
                covered=sum(atom in normalized(output) for atom in atoms)
                citations=re.findall(r'\[source:([^\]]+)\]',output)
                item.update({'answer':output,'generated_tokens':int(result.shape[1]-prefix.shape[1]),'elapsed_seconds':time.monotonic()-begun,
                    'literal_atom_coverage':covered/len(atoms),'known_citation_present':'1' in citations,'invalid_citations':sum(c!='1' for c in citations),
                    'semantic_entailment':'not_independently_graded','source_ids':row['source_ids']})
            results.append(item)
            if len(results)%10==0:print(json.dumps({'evaluation':tag,'completed':len(results),'total':len(rows)}),flush=True)
    generated=[r for r in results if 'answer' in r]
    report={'stage':tag,'split':'selection','contexts':'fixed oracle positive-source excerpts, same questions/context for compared models',
        'examples':len(rows),'generated_examples':len(generated),'teacher_forced_nll':total_loss/max(1,total_tokens),
        'literal_atom_coverage':sum(r['literal_atom_coverage'] for r in generated)/max(1,len(generated)),
        'known_citation_rate':sum(r['known_citation_present'] for r in generated)/max(1,len(generated)),
        'invalid_citation_count':sum(r['invalid_citations'] for r in generated),'semantic_quality_acceptance':False,
        'limitations':['Automatic literal-span labels; no independent human judgement','Oracle contexts isolate generator; this is not retrieval quality or end-to-end RAG','Limited stratified generated subset; teacher-forced loss is diagnostic only'],
        'selection_file_sha256':digest(OUT/'data/selection-fixed-context.jsonl'),'final_read':False}
    write_jsonl(out/'answers.jsonl',results);write_json(out/'metrics.json',report)
    model.train();return report

def main():
    p=argparse.ArgumentParser();p.add_argument('--experiment',choices=['A','B','partial08'],required=True)
    p.add_argument('--resume',action='store_true');p.add_argument('--steps',type=int,default=16)
    p.add_argument('--accumulation',type=int,default=0);p.add_argument('--feasibility-microsteps',type=int,default=2)
    p.add_argument('--evaluate',action='store_true');p.add_argument('--skip-generation',action='store_true')
    p.add_argument('--lease-wait',type=int,default=3600);p.add_argument('--minimum-free-vram-mib',type=int,default=1500)
    args=p.parse_args();offline()
    import numpy as np
    import torch
    import psutil
    from transformers import AutoTokenizer
    name='Qwen3.5-0.8B' if args.experiment=='partial08' else 'Qwen3.5-4B';mode='partial' if args.experiment=='partial08' else 'qlora'
    run=OUT/args.experiment;run.mkdir(parents=True,exist_ok=True)
    rows=read_jsonl(OUT/'data'/('train-'+('B' if args.experiment=='B' else 'A')+'.jsonl'))
    selection=read_jsonl(OUT/'data/selection-fixed-context.jsonl')
    generation_ids=set() if args.skip_generation else set(json.loads((OUT/'data/generation-case-ids.json').read_text()))
    accumulation=args.accumulation or math.ceil(len(rows)/16);total_steps=math.ceil(len(rows)/accumulation)
    if not rows:raise ValueError('No verified training examples')
    tokenizer=AutoTokenizer.from_pretrained(BASES/name,local_files_only=True)
    if digest(BASES/name/'tokenizer.json')!=digest(BASES/'Qwen3.5-4B/tokenizer.json'):raise ValueError('Retokenize the compact foundation before comparison')
    lock=foundation_lock(name);write_json(run/'foundation-lock.json',lock)
    config={'experiment':args.experiment,'foundation':name,'foundation_revision':lock['revision'],'foundation_lock_sha256':digest(run/'foundation-lock.json'),
        'mode':mode,'seed':SEED,'max_length':json.loads((OUT/'data/manifest.json').read_text())['max_length'],'learning_rate':1e-4 if mode=='qlora' else 2e-5,
        'loss':'causal cross entropy over assistant answer positions only; token-weighted gradient accumulation','optimizer':'AdamW','weight_decay':.01,
        'lora_rank':8 if mode=='qlora' else None,'lora_alpha':16 if mode=='qlora' else None,'lora_dropout':0.,'target_regex':TARGETS if mode=='qlora' else 'last four text blocks; embeddings/vision/remaining blocks frozen',
        'accumulation':accumulation,'total_epoch_steps':total_steps,'train_examples':len(rows),'independent_intents':len({r['intent_id'] for r in rows}),
        'train_sha256':digest(OUT/'data'/('train-'+('B' if args.experiment=='B' else 'A')+'.jsonl')),'selection_sha256':digest(OUT/'data/selection-fixed-context.jsonl'),
        'source_code_sha256':digest(__file__),'old_adapters_used':False,'human_verified':False,'final_read':False,
        'versions':{k:importlib.metadata.version(k) for k in ('torch','transformers','peft','bitsandbytes','accelerate','safetensors')}}
    write_json(run/'config.json',config)
    required_ram=(12 if mode=='qlora' else 8)*1024**3
    waiting=False
    while psutil.virtual_memory().available<required_ram:
        if not waiting:print('Waiting for native build/other work to release RAM; no foreign processes are closed',flush=True);waiting=True
        time.sleep(5)
    with gpu_lease('Generator '+args.experiment+' '+mode, args.lease_wait):
        if psutil.virtual_memory().available<required_ram:raise MemoryError('Host RAM fell below the load reserve; retry after other work completes')
        if not torch.cuda.is_available():raise RuntimeError('CUDA unavailable')
        torch.set_num_threads(4);random.seed(SEED);np.random.seed(SEED);torch.manual_seed(SEED);torch.cuda.manual_seed_all(SEED)
        model=load_model(torch,name,mode);model.train();torch.cuda.reset_peak_memory_stats()
        params=[p for p in model.parameters() if p.requires_grad]
        config['trainable_parameters']=sum(p.numel() for p in params)
        write_json(run/'trainable-parameters.json',{'names':[n for n,p in model.named_parameters() if p.requires_grad],'count':config['trainable_parameters'],'all_parameters':sum(p.numel() for p in model.parameters())})
        optimizer=torch.optim.AdamW(params,lr=config['learning_rate'],weight_decay=.01)
        scheduler=torch.optim.lr_scheduler.LambdaLR(optimizer,lambda step:min(1.,(step+1)/2)*max(0.,1-step/max(1,total_steps)))
        order=list(range(len(rows)));random.Random(SEED).shuffle(order);cursor=0;step=0
        initial=trainable_state(model);initial_hashes=fingerprint(torch,initial);del initial
        if args.resume:
            state=torch.load(run/'checkpoint.pt',map_location='cpu',weights_only=False)
            if state['config']!=config:raise ValueError('Checkpoint configuration/data/foundation/code changed')
            named=dict(model.named_parameters())
            with torch.no_grad():
                for key,value in state['trainable'].items():named[key].copy_(value.to(named[key].device))
            optimizer.load_state_dict(state['optimizer']);scheduler.load_state_dict(state['scheduler'])
            order=state['order'];cursor=state['cursor'];step=state['step'];initial_hashes=state['initial_hashes'];restore_rng(torch,np,state['rng'])
            del state
        else:
            state=rng_state(torch,np);pilot=[]
            for index in order[:args.feasibility_microsteps]:
                begun=time.monotonic();loss,count=loss_for(torch,model,rows[index]);(loss/count).backward()
                pilot.append({'id':rows[index]['id'],'loss':float(loss)/count,'microstep_seconds':time.monotonic()-begun,'peak_vram_allocated':torch.cuda.max_memory_allocated(),'host_rss':psutil.Process().memory_info().rss})
                optimizer.zero_grad(set_to_none=True)
            restore_rng(torch,np,state)
            write_json(run/'feasibility.json',{'microsteps':pilot,'optimizer_updates':0,'checkpointing':True,'finite':all(math.isfinite(r['loss']) for r in pilot),'state_restored':True})
            if not all(math.isfinite(r['loss']) for r in pilot):raise FloatingPointError('Nonfinite pilot loss')
        if args.evaluate and step==0:
            baseline_dir=OUT/('baseline08' if mode=='partial' else 'baseline4B')
            if not (baseline_dir/'metrics.json').exists():
                if mode=='qlora':
                    with model.disable_adapter():evaluate(torch,model,tokenizer,selection,generation_ids,baseline_dir,'unchanged_foundation_NF4')
                else:evaluate(torch,model,tokenizer,selection,generation_ids,baseline_dir,'unchanged_foundation_BF16_last4_FP32')
        optimizer.zero_grad(set_to_none=True)
        while cursor<len(order) and step<min(args.steps,total_steps):
            free,total=torch.cuda.mem_get_info()
            if free<args.minimum_free_vram_mib*1024**2:raise MemoryError('Dedicated GPU reserve breached; checkpoint remains at last complete optimizer step')
            group=order[cursor:cursor+accumulation];denominator=sum(rows[i]['target_tokens'] for i in group);loss_sum=0.;began=time.monotonic()
            for index in group:
                loss,count=loss_for(torch,model,rows[index]);(loss/denominator).backward();loss_sum+=float(loss)
            gradient=float(torch.nn.utils.clip_grad_norm_(params,1.))
            if not math.isfinite(loss_sum) or not math.isfinite(gradient):raise FloatingPointError('Nonfinite training loss/gradient')
            optimizer.step();scheduler.step();optimizer.zero_grad(set_to_none=True);step+=1;cursor+=len(group)
            event={'step':step,'cursor':cursor,'loss':loss_sum/denominator,'gradient_norm':gradient,'learning_rate':scheduler.get_last_lr()[0],
                'optimizer_step_seconds':time.monotonic()-began,'peak_vram_allocated':torch.cuda.max_memory_allocated(),'peak_vram_reserved':torch.cuda.max_memory_reserved(),'host_rss':psutil.Process().memory_info().rss}
            log(run/'training-log.jsonl',event);print(json.dumps(event),flush=True)
            if step in (1,2,4,8,16) or cursor==len(order) or step==args.steps:
                atomic_checkpoint(torch,run/'checkpoint.pt',{'config':config,'trainable':trainable_state(model),'optimizer':optimizer.state_dict(),'scheduler':scheduler.state_dict(),
                    'rng':rng_state(torch,np),'order':order,'cursor':cursor,'step':step,'initial_hashes':initial_hashes,'grad_scaler':None,'precision':'BF16 autocast; no loss scaler'})
        final=trainable_state(model);changed=sum(initial_hashes.get(n)!=h for n,h in fingerprint(torch,final).items())
        if changed==0 and step>0:raise RuntimeError('No trainable tensor changed')
        if mode=='qlora':model.save_pretrained(run/'adapter',safe_serialization=True);tokenizer.save_pretrained(run/'adapter')
        else:
            from safetensors.torch import save_file
            save_file({k:v.contiguous() for k,v in final.items()},str(run/'partial-base-weights.safetensors'))
            write_json(run/'partial-base-weights.json',{'base':name,'revision':lock['revision'],'updated_parameters':list(final),'replacement_not_lora':True})
        report={'status':'one_epoch_complete' if cursor==len(order) else 'pilot_checkpoint','experiment':args.experiment,'steps':step,'total_epoch_steps':total_steps,
            'examples_consumed':cursor,'independent_intents':config['independent_intents'],'answer_tokens_consumed':sum(rows[i]['target_tokens'] for i in order[:cursor]),
            'trainable_parameters':config['trainable_parameters'],'changed_trainable_tensors':changed,'base_weights_changed':mode=='partial','adapter_weights_changed':mode=='qlora',
            'peak_vram_allocated':torch.cuda.max_memory_allocated(),'peak_vram_reserved':torch.cuda.max_memory_reserved(),'quality_acceptance':False,'final_read':False}
        write_json(run/'training-report.json',report)
        if args.evaluate:evaluate(torch,model,tokenizer,selection,generation_ids,run/'selection','trained_'+args.experiment)
        print(json.dumps(report),flush=True)

if __name__=='__main__':
    try:main()
    except BaseException as error:
        OUT.mkdir(parents=True,exist_ok=True)
        log(OUT/'failures.jsonl',{'argv':sys.argv,'error':repr(error),'traceback':traceback.format_exc()})
        raise
