"""Frozen reranker versus original order on a fixed E5 top-24 pool."""
import argparse,collections,json,math,pathlib,sqlite3,time
import numpy as np
from transformers import AutoTokenizer
from adequacy_stress import session,inputs
from bge_reranker import OUT as BGE
from e5_train import MODELS
from data_pipeline import ROOT,DEFAULT_OUT,read_jsonl,write_json,write_jsonl
from e5_compare import interval

def main():
    p=argparse.ArgumentParser();p.add_argument('--variant',choices=['baseline','ordinary'],default='ordinary');p.add_argument('--limit',type=int,default=0);p.add_argument('--pool-size',type=int,default=24);a=p.parse_args()
    ix=ROOT/f'src/build/research-20261024/rag-index-{a.variant}';im=json.loads((ix/'index.json').read_text())
    mm=read_jsonl(ix/'fragments.jsonl');vectors=np.memmap(ix/'vectors.f32',dtype='<f4',mode='r',shape=(im['rows'],384));positions={r['fragment_id']:i for i,r in enumerate(mm)}
    examples=read_jsonl(DEFAULT_OUT/'selection.jsonl');examples=examples[:a.limit] if a.limit else examples
    et=AutoTokenizer.from_pretrained(MODELS/a.variant/'onnx/tokenizer',local_files_only=True);es=session(MODELS/a.variant/'onnx/encoder.onnx')
    bt=AutoTokenizer.from_pretrained(BGE/'onnx/tokenizer',local_files_only=True);bs=session(BGE/'onnx/reranker.onnx')
    con=sqlite3.connect((ROOT/'db/index.sqlite').as_uri()+'?mode=ro&immutable=1',uri=True);con.row_factory=sqlite3.Row
    result=[];baseline=[];times=[];out=BGE/f'evaluation-{a.variant}';out.mkdir(exist_ok=True)
    for number,e in enumerate(examples,1):
        q=es.run(['embedding'],inputs(et,['query: '+e['question']]))[0][0];cos=np.asarray(vectors@q)
        ranking=np.argsort(-cos,kind='stable');top=ranking[:a.pool_size]
        source_rows=[dict(con.execute('SELECT fragment_id,text FROM chunks WHERE fragment_id=?',(mm[j]['fragment_id'],)).fetchone()) for j in top]
        pairs=[[e['question'],s['text']] for s in source_rows];scores=[];start=time.perf_counter()
        for offset in range(0,len(pairs),4):scores.extend(bs.run(['scores'],inputs(bt,pairs[offset:offset+4]))[0].tolist())
        elapsed=time.perf_counter()-start;times.append(elapsed)
        reranked=np.argsort(-np.asarray(scores),kind='stable');reranked_ids=[source_rows[j]['fragment_id'] for j in reranked]
        originalrank=min(int(np.where(ranking==positions[p])[0][0])+1 for p in e['positive_ids'] if p in positions)
        newrank=min((reranked_ids.index(p)+1 for p in e['positive_ids'] if p in reranked_ids),default=originalrank)
        base={'id':e['id'],'language':e['language'],'family_id':e['family_ids'][0]}
        def metric(rank):return {'rank':rank,'recall_at_5':float(rank<=5),'recall_at_10':float(rank<=10),'ndcg_at_10':1/math.log2(rank+1) if rank<=10 else 0.,'mrr':1/rank}
        baseline.append(base|metric(originalrank));result.append(base|metric(newrank)|{'original_rank':originalrank,'positive_in_fixed_pool':originalrank<=a.pool_size,'top10':reranked_ids[:10],'original_pool_ids':[s['fragment_id'] for s in source_rows],'reranked_pool_ids':reranked_ids,'bge_logits':scores,'reranking_seconds':elapsed})
        write_jsonl(out/'results-progress.jsonl',result)
        print(json.dumps({'case':number,'total':len(examples),'original_rank':originalrank,'reranked_rank':newrank,'cpu_reranking_seconds':elapsed}),flush=True)
    con.close();out=BGE/f'evaluation-{a.variant}'
    out.mkdir(exist_ok=True)
    summary={'variant':a.variant,'count':len(examples),'fixed_candidate_pool':a.pool_size,'weights_frozen':True,'final_used':False,'threads':4,'cpu_timing_scope':'Preparation host under possible concurrent load; not clean recipient performance acceptance','metrics':{name:{m:float(np.mean([r[m] for r in rows])) for m in ('recall_at_5','recall_at_10','ndcg_at_10','mrr')} for name,rows in [('original_e5_order',baseline),('frozen_bge_order',result)]},'paired_ci':{m:interval(baseline,result,m) for m in ('recall_at_10','ndcg_at_10')},'cpu_rerank_seconds_median':float(np.median(times)),'cpu_rerank_seconds_p95':float(np.quantile(times,.95)),'top1_errors_before':sum(r['rank']>1 for r in baseline),'top1_errors_after':sum(r['rank']>1 for r in result),'positive_outside_fixed_pool':sum(not r['positive_in_fixed_pool'] for r in result),'limits':['Source-verified extraction proxy; no independent semantic evaluation','Five selection families; one dominates','No final questions or labels read']}
    write_jsonl(out/'results.jsonl',result);write_json(out/'metrics.json',summary);print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
