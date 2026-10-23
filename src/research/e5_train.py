"""Full-parameter E5 negative-sampling comparison, frozen final split.

GPU use requires explicit --allow-cuda. No paid APIs, no corpus upload.
The short pilot is not a completed epoch or a quality acceptance.
"""
from __future__ import annotations
import argparse, collections, contextlib, copy, hashlib, json, math, os, pathlib, random, time, uuid
import numpy as np
import torch
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer
from data_pipeline import DEFAULT_OUT, ROOT, SEED, digest, hid, read_jsonl, write_json, write_jsonl

BASE=ROOT/'src/build/research-20261024/models/e5/base'
MODELS=BASE.parent

@contextlib.contextmanager
def gpu_lease(enabled):
    if not enabled:
        yield;return
    path=ROOT/'src/build/research-20261024/gpu.lock'; nonce=uuid.uuid4().hex
    payload={'pid':os.getpid(),'agent':'audit_research','task':'E5 full fine tuning / validation','nonce':nonce}
    while True:
        try:
            fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
            with os.fdopen(fd,'w') as f:json.dump(payload,f)
            break
        except FileExistsError:
            print('GPU lease occupied; waiting 5 seconds',flush=True);time.sleep(5)
    try:yield
    finally:
        if path.exists() and json.loads(path.read_text()).get('nonce')==nonce:path.unlink()

def safe_read(path):
    if 'sealed-final' in pathlib.Path(path).parts: raise ValueError('Final split cannot be opened by training/selection')
    return read_jsonl(path)

def tokens(s):return set(__import__('re').findall(r'\w+',s.casefold()))
def prepare_triples(data, out):
    sources={s['fragment_id']:s for s in safe_read(data/'sources.jsonl')}
    train=safe_read(data/'train.jsonl'); train_ids={p for e in train for p in e['positive_ids']}
    rows={variant:[] for variant in ('ordinary','hard')}; rng=random.Random(SEED)
    for ex in train:
        pos=sources[ex['positive_ids'][0]]
        # Different family + explicitly named requested document establishes a
        # document-identity negative. This is not universal semantic non-entailment.
        pool=[sources[p] for p in sorted(train_ids) if sources[p]['family_id']!=pos['family_id'] and sources[p]['document_id']!=pos['document_id']]
        if not pool:continue
        qwords=tokens(ex['question']); pool.sort(key=lambda s:(-len(qwords & tokens(s['text']+' '+s['title']))/max(1,len(qwords|tokens(s['text']+' '+s['title']))),s['fragment_id']))
        for variant,negative in [('ordinary',rng.choice(pool)),('hard',pool[0])]:
            rows[variant].append({'query_id':ex['id'],'query':ex['question'],'positive_id':pos['fragment_id'],'negative_id':negative['fragment_id'],'negative_kind':variant,'negative_verification':'different_family_and_document_in_document_qualified_question','semantic_non_entailment':'not_independently_verified','positive_verification':pos['verification']})
    for variant,rr in rows.items():write_jsonl(out/(variant+'-triples.jsonl'),rr)
    write_json(out/'triples-manifest.json',{'seed':SEED,'counts':{k:len(v) for k,v in rows.items()},'train_sha256':digest(data/'train.jsonl'),'scope':'Document-qualified native extraction, not broad ordinary-question semantic supervision','final_used':False})
    return rows

def pooled(output, mask):
    x=output.last_hidden_state.masked_fill(~mask[...,None].bool(),0.)
    return F.normalize(x.sum(dim=1)/mask.sum(dim=1)[...,None],p=2,dim=1)

def encode(model, tokenizer, texts, device, max_length=256, batch_size=8):
    result=[];model.eval()
    with torch.inference_mode():
        for i in range(0,len(texts),batch_size):
            batch=tokenizer(texts[i:i+batch_size],padding=True,truncation=True,max_length=max_length,return_tensors='pt').to(device)
            result.append(pooled(model(**batch),batch['attention_mask']).float().cpu())
    return torch.cat(result) if result else torch.zeros((0,model.config.hidden_size))

def evaluate(model, tokenizer, data, device, out, max_length=256):
    sources=safe_read(data/'sources.jsonl'); examples=safe_read(data/'selection.jsonl')
    # Corpus text may be indexed at evaluation, including final families. No
    # final questions, labels, modes or scores are read here.
    embeddings=encode(model,tokenizer,['passage: '+s['text'] for s in sources],device,max_length)
    queries=encode(model,tokenizer,['query: '+e['question'] for e in examples],device,max_length)
    scores=queries@embeddings.T; rows=[]
    for ex,ss in zip(examples,scores):
        indices=torch.argsort(ss,descending=True).tolist(); ranked=[sources[i]['fragment_id'] for i in indices]
        rank=min((ranked.index(p)+1 for p in ex['positive_ids'] if p in ranked),default=len(ranked)+1)
        rows.append({'id':ex['id'],'language':ex['language'],'family_id':ex['family_ids'][0],'recall_at_5':float(rank<=5),'recall_at_10':float(rank<=10),'ndcg_at_10':1/math.log2(rank+1) if rank<=10 else 0.,'mrr':1/rank,'rank':rank,'top10':ranked[:10]})
    metrics={name:float(np.mean([r[name] for r in rows])) if rows else None for name in ('recall_at_5','recall_at_10','ndcg_at_10','mrr')}
    report={'split':'selection','count':len(rows),'families':len({r['family_id'] for r in rows}),'metrics':metrics,'languages':{lang:{m:float(np.mean([r[m] for r in rows if r['language']==lang])) for m in metrics} for lang in sorted({r['language'] for r in rows})},'limits':['Native anchor-assisted extraction','No final labels read','Small family count makes confidence intervals weak'],'sources_sha256':digest(data/'sources.jsonl'),'selection_sha256':digest(data/'selection.jsonl')}
    out.mkdir(parents=True,exist_ok=True);write_json(out/'retrieval-metrics.json',report);write_jsonl(out/'retrieval-results.jsonl',rows)
    return report

def save_checkpoint(path, model, optimizer, scheduler, step, order, cursor, config):
    state={'model':model.state_dict(),'optimizer':optimizer.state_dict(),'scheduler':scheduler.state_dict(),'step':step,'order':order,'cursor':cursor,'python_rng':random.getstate(),'numpy_rng':np.random.get_state(),'torch_rng':torch.get_rng_state(),'cuda_rng':torch.cuda.get_rng_state_all() if torch.cuda.is_available() else None,'config':config,'grad_scaler':None,'precision':'BF16 autocast has no loss scaler'}
    tmp=path.with_suffix('.partial');torch.save(state,tmp);os.replace(tmp,path)
    if step in (2,16,config['total_epoch_steps']):
        immutable=path.with_name(f'checkpoint-step-{step:04d}.pt')
        if not immutable.exists():os.link(path,immutable)

def train(args):
    if args.device=='cuda' and not args.allow_cuda:raise ValueError('GPU requires --allow-cuda')
    data=args.data.resolve()
    run_name=args.run_name or args.variant
    if not __import__('re').fullmatch(r'[a-z0-9_-]+',run_name):raise ValueError('Unsafe run name')
    out=MODELS/run_name;out.mkdir(parents=True,exist_ok=True)
    torch.manual_seed(args.seed);random.seed(args.seed);np.random.seed(args.seed);torch.set_num_threads(4)
    triples_path=data/(args.variant+'-triples.jsonl')
    if not triples_path.exists():prepare_triples(data,data)
    triples=safe_read(triples_path);sources={s['fragment_id']:s for s in safe_read(data/'sources.jsonl')}
    if len(triples)<2:raise ValueError('Insufficient training triples')
    tokenizer=AutoTokenizer.from_pretrained(BASE,local_files_only=True)
    model=AutoModel.from_pretrained(BASE,local_files_only=True).to(args.device)
    for p in model.parameters():p.requires_grad_(True)
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False})
    optimizer=torch.optim.AdamW(model.parameters(),lr=args.lr,weight_decay=.01)
    # Fixed one-epoch schedule allows a 16-step pilot to resume to the same epoch.
    total_steps=math.ceil(len(triples)/args.accumulation)
    scheduler=torch.optim.lr_scheduler.LambdaLR(optimizer,lambda step:min(1.,(step+1)/max(1,int(total_steps*.1)))*max(0.,1-step/max(1,total_steps)))
    order=list(range(len(triples)));random.Random(args.seed).shuffle(order);cursor=0;step=0
    config={'seed':args.seed,'variant':args.variant,'loss':'two-candidate InfoNCE; cosine / temperature','temperature':.05,'learning_rate':args.lr,'optimizer':'AdamW','weight_decay':.01,'accumulation':args.accumulation,'max_length':args.max_length,'trainable':'all encoder base weights','frozen':[],'parameter_count':sum(p.numel() for p in model.parameters()),'total_epoch_steps':total_steps,'train_sha256':digest(data/'train.jsonl'),'triples_sha256':digest(triples_path),'foundation_lock_sha256':digest(MODELS/'foundation-lock.json'),'final_used':False}
    if args.resume:
        state=torch.load(out/'checkpoint.pt',map_location=args.device,weights_only=False)
        assert state['config']==config,'Resume configuration changed'
        model.load_state_dict(state['model']);optimizer.load_state_dict(state['optimizer']);scheduler.load_state_dict(state['scheduler']);step=state['step'];order=state['order'];cursor=state['cursor'];random.setstate(state['python_rng']);np.random.set_state(state['numpy_rng']);torch.set_rng_state(state['torch_rng'].cpu())
        if state['cuda_rng'] is not None:torch.cuda.set_rng_state_all([rng.cpu() for rng in state['cuda_rng']])
    max_steps=total_steps if args.steps==0 else min(total_steps,args.steps)
    write_json(out/'config.json',config)
    model.train();optimizer.zero_grad(set_to_none=True);losses=[]
    while cursor<len(order) and step<max_steps:
        group=order[cursor:cursor+args.accumulation];loss_sum=0.
        for j in group:
            row=triples[j]
            texts=['query: '+row['query'],'passage: '+sources[row['positive_id']]['text'],'passage: '+sources[row['negative_id']]['text']]
            batch=tokenizer(texts,padding=True,truncation=True,max_length=args.max_length,return_tensors='pt').to(args.device)
            with torch.autocast(device_type=args.device,dtype=torch.bfloat16,enabled=args.device=='cuda'):
                emb=pooled(model(**batch),batch['attention_mask']);logits=(emb[:1]@emb[1:].T)/.05
                loss=F.cross_entropy(logits.float(),torch.zeros(1,dtype=torch.long,device=args.device))
            (loss/len(group)).backward();loss_sum+=loss.item()
        grad=float(torch.nn.utils.clip_grad_norm_(model.parameters(),1.))
        optimizer.step();scheduler.step();optimizer.zero_grad(set_to_none=True);step+=1;cursor+=len(group)
        row={'step':step,'cursor':cursor,'loss':loss_sum/len(group),'gradient_norm':grad,'learning_rate':scheduler.get_last_lr()[0]};losses.append(row)
        with (out/'training-log.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(row)+'\n')
        if step in (1,2,4,16) or step==max_steps:save_checkpoint(out/'checkpoint.pt',model,optimizer,scheduler,step,order,cursor,config)
        print(json.dumps(row),flush=True)
    model.save_pretrained(out/'encoder',safe_serialization=True);tokenizer.save_pretrained(out/'encoder')
    report={'status':'one_epoch_complete' if cursor>=len(order) else 'pilot_only_not_completed_epoch','steps':step,'total_epoch_steps':total_steps,'examples_consumed':cursor,'variant':args.variant,'trainable_parameters':config['parameter_count'],'full_base_weight_update':True,'resume_checkpoint':str(out/'checkpoint.pt'),'final_used':False,'peak_vram_allocated':torch.cuda.max_memory_allocated() if args.device=='cuda' else 0,'quality_acceptance':False}
    write_json(out/'training-report.json',report)
    if args.evaluate:
        evaluation=evaluate(model,tokenizer,data,args.device,out,args.max_length)
        write_json(out/f'retrieval-metrics-step-{step:04d}.json',evaluation)
    print(json.dumps(report),flush=True)

def run(a):
    if a.command=='prepare':prepare_triples(a.data,a.data);return
    if a.command=='train':train(a);return
    torch.set_num_threads(4);path=BASE if a.command=='baseline' else MODELS/a.variant/'encoder'
    tokenizer=AutoTokenizer.from_pretrained(path,local_files_only=True);model=AutoModel.from_pretrained(path,local_files_only=True).to(a.device)
    print(json.dumps(evaluate(model,tokenizer,a.data,a.device,MODELS/('baseline' if a.command=='baseline' else a.variant),a.max_length),indent=2))

def main():
    p=argparse.ArgumentParser();p.add_argument('command',choices=['prepare','baseline','train','evaluate']);p.add_argument('--data',type=pathlib.Path,default=DEFAULT_OUT);p.add_argument('--variant',choices=['ordinary','hard'],default='ordinary');p.add_argument('--device',choices=['cpu','cuda'],default='cpu');p.add_argument('--allow-cuda',action='store_true');p.add_argument('--steps',type=int,default=16);p.add_argument('--accumulation',type=int,default=4);p.add_argument('--max-length',type=int,default=256);p.add_argument('--lr',type=float,default=2e-5);p.add_argument('--seed',type=int,default=SEED);p.add_argument('--run-name',default='');p.add_argument('--resume',action='store_true');p.add_argument('--evaluate',action='store_true');a=p.parse_args()
    if a.device=='cuda' and not a.allow_cuda:raise ValueError('GPU requires --allow-cuda')
    with gpu_lease(a.device=='cuda'):run(a)
if __name__=='__main__':main()
