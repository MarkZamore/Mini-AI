"""Compare top8/12 reranking while retaining the untouched candidate tail.

Reuse saved full reranker order whenever exact. A legacy run retained only its
top10; ambiguous demoted positives are rescored on CPU for that small subset.
"""
import json,math,sqlite3,time
import numpy as np
from transformers import AutoTokenizer
from data_pipeline import ROOT,DEFAULT_OUT,read_jsonl,write_json,write_jsonl
from adequacy_stress import session,inputs
from e5_train import MODELS
from bge_reranker import OUT as BGE
from e5_compare import interval

def main():
    ix=ROOT/'src/build/research-20261024/rag-index-ordinary';im=json.loads((ix/'index.json').read_text());metadata=read_jsonl(ix/'fragments.jsonl');positions={r['fragment_id']:i for i,r in enumerate(metadata)}
    vectors=np.memmap(ix/'vectors.f32',dtype='<f4',mode='r',shape=(im['rows'],384));examples=read_jsonl(DEFAULT_OUT/'selection.jsonl')
    old={r['id']:r for r in read_jsonl(BGE/'evaluation-ordinary/results.jsonl')};baseline=read_jsonl(ix/'full-retrieval-results.jsonl')
    et=AutoTokenizer.from_pretrained(MODELS/'ordinary/onnx/tokenizer',local_files_only=True);es=session(MODELS/'ordinary/onnx/encoder.onnx')
    bt=AutoTokenizer.from_pretrained(BGE/'onnx/tokenizer',local_files_only=True);bs=None
    con=sqlite3.connect((ROOT/'db/index.sqlite').as_uri()+'?mode=ro&immutable=1',uri=True);out={8:[],12:[]};rescored=[]
    for e in examples:
        r=old[e['id']];q=es.run(['embedding'],inputs(et,['query: '+e['question']]))[0][0];scores=np.asarray(vectors@q);ranking=np.argsort(-scores,kind='stable')
        pool=[metadata[i]['fragment_id'] for i in ranking[:24]];gold=e['positive_ids'][0];originalrank=int(np.where(ranking==positions[gold])[0][0])+1
        for n in (8,12):
            if originalrank>n:
                rank=originalrank;method='outside_subset_tail_preserved';elapsed=None
            elif 'reranked_pool_ids' in r:
                restricted=[i for i in r['reranked_pool_ids'] if i in pool[:n]];rank=restricted.index(gold)+1;method='exact_full_cached_order';elapsed=None
            elif gold in r['top10']:
                preceding=r['top10'][:r['top10'].index(gold)];rank=1+sum(i in pool[:n] for i in preceding);method='exact_legacy_top10_order';elapsed=None
            else:
                if bs is None:bs=session(BGE/'onnx/reranker.onnx')
                pairs=[[e['question'],con.execute('SELECT text FROM chunks WHERE fragment_id=?',(i,)).fetchone()[0]] for i in pool[:n]]
                start=time.perf_counter();logits=[]
                for j in range(0,n,4):logits.extend(bs.run(['scores'],inputs(bt,pairs[j:j+4]))[0].tolist())
                order=np.argsort(-np.asarray(logits),kind='stable');restricted=[pool[i] for i in order];rank=restricted.index(gold)+1;elapsed=time.perf_counter()-start;method='CPU_rescore_ambiguous_legacy_case';rescored.append({'id':e['id'],'pool':n,'seconds':elapsed,'logits':logits})
            out[n].append({'id':e['id'],'language':e['language'],'family_id':e['family_ids'][0],'rank':rank,'recall_at_5':float(rank<=5),'recall_at_10':float(rank<=10),'ndcg_at_10':1/math.log2(rank+1) if rank<=10 else 0.,'mrr':1/rank,'method':method,'cpu_rescore_seconds':elapsed})
    con.close();report={'candidate_tail_preserved':True,'new_GPU_experiments':False,'ambiguous_legacy_cases_CPU_rescored':rescored,'metrics':{},'latency_status':'Top8/12 latency not inferred as measured from24; native subset latency must be measured separately','final_read':False,'limitations':['Automatic native-span relevance proxy','Legacy top10 caching required a few CPU subset rescoring operations','Semantic completeness unknown']}
    for name,rows in [('e5_original',baseline),('bge24',list(old.values())),('bge8',out[8]),('bge12',out[12])]:report['metrics'][name]={m:float(np.mean([r[m] for r in rows])) for m in ('recall_at_5','recall_at_10','ndcg_at_10','mrr')}
    report['paired_CI']={str(n):{m:interval(baseline,out[n],m) for m in ('recall_at_10','ndcg_at_10')} for n in (8,12)}
    for n in (8,12):write_jsonl(BGE/f'evaluation-ordinary/results-top{n}.jsonl',out[n])
    write_json(BGE/'evaluation-ordinary/subset-comparison.json',report);print(json.dumps(report,indent=2))
if __name__=='__main__':main()
