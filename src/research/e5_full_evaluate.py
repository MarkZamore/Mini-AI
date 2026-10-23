"""Runtime-aligned CPU ORT queries and deterministic stable full-index ranks."""
import argparse,json,math,shutil
import numpy as np
from transformers import AutoTokenizer
from adequacy_stress import session,inputs
from data_pipeline import ROOT,DEFAULT_OUT,read_jsonl,write_json,write_jsonl,digest
from e5_train import MODELS

def main():
    p=argparse.ArgumentParser();p.add_argument('--variant',choices=['baseline','ordinary','ordinary-seed2'],required=True);a=p.parse_args()
    ix=ROOT/f'src/build/research-20261024/rag-index-{a.variant}';im=json.loads((ix/'index.json').read_text());rows=read_jsonl(ix/'fragments.jsonl');pos={r['fragment_id']:i for i,r in enumerate(rows)}
    vectors=np.memmap(ix/'vectors.f32',dtype='<f4',mode='r',shape=(len(rows),384))
    tok=AutoTokenizer.from_pretrained(MODELS/a.variant/'onnx/tokenizer',local_files_only=True);model=session(MODELS/a.variant/'onnx/encoder.onnx')
    ex=read_jsonl(DEFAULT_OUT/'selection.jsonl');result=[]
    for e in ex:
        q=model.run(['embedding'],inputs(tok,['query: '+e['question']]))[0][0];scores=np.asarray(vectors@q);order=np.argsort(-scores,kind='stable')
        rank=min(int(np.where(order==pos[p])[0][0])+1 for p in e['positive_ids'] if p in pos)
        result.append({'id':e['id'],'language':e['language'],'family_id':e['family_ids'][0],'recall_at_5':float(rank<=5),'recall_at_10':float(rank<=10),'ndcg_at_10':1/math.log2(rank+1) if rank<=10 else 0.,'mrr':1/rank,'rank':rank,'top10':[rows[i]['fragment_id'] for i in order[:10]]})
    names=('recall_at_5','recall_at_10','ndcg_at_10','mrr')
    report={'variant':a.variant,'candidate_pool':len(rows),'selection_examples':len(ex),'metrics':{m:float(np.mean([r[m] for r in result])) for m in names},'languages':{l:{m:float(np.mean([r[m] for r in result if r['language']==l])) for m in names} for l in sorted({r['language'] for r in result})},'selection_sha256':digest(DEFAULT_OUT/'selection.jsonl'),'final_read':False,'query_runtime':'CPU ONNX Runtime, pooled normalized E5, max256','rank_ties':'Stable by original fragment-ID-sorted index row, never optimistic ties','correction_scope':'Uniform scorer correction and production-runtime alignment before final disclosure; no model/data/threshold tuning','limits':['Native automatic labels, no independent semantic gold','Five selection families; one has72of80questions']}
    for name in ('full-retrieval-results.jsonl','full-retrieval-metrics.json'):
        old=ix/name;backup=ix/(name+'.before-stable-ORT-correction')
        if old.exists() and not backup.exists():shutil.copy2(old,backup)
    write_jsonl(ix/'full-retrieval-results.jsonl',result);write_json(ix/'full-retrieval-metrics.json',report);print(json.dumps(report,indent=2))
if __name__=='__main__':main()
