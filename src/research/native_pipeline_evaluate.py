"""Paired actual full-composition SearchForAssistant evaluation on selection80."""
import argparse,collections,json,pathlib,hashlib
import numpy as np
from data_pipeline import ROOT,DEFAULT_OUT,read_jsonl,write_json,write_jsonl,digest
from e5_compare import interval

def evaluate(path,examples):
    raw=read_jsonl(path);ids=[r['id'] for r in raw];expected={e['id']:e for e in examples}
    assert len(raw)==80 and len(set(ids))==80 and set(ids)==set(expected),'Complete exact selection80 required'
    rows=[];modules=set();timings=[]
    for r in raw:
        e=expected[r['id']];assert r['question']==e['question'],'Question wording changed'
        result=r.get('result',{});values=result.get('value') or [];ranked=[x['fragment_id'] for x in values]
        assert len(ranked)==len(set(ranked)),'Repeated fragment IDs in actual result'
        rank=min((ranked.index(p)+1 for p in e['positive_ids'] if p in ranked),default=None)
        modules.add(hashlib.sha256(json.dumps(r.get('modules'),sort_keys=True,ensure_ascii=False).encode()).hexdigest())
        if r.get('fullSeconds') is not None:timings.append(float(r['fullSeconds']))
        rows.append({'id':e['id'],'language':e['language'],'family_id':e['family_ids'][0],'rank_in_returned_list':rank,'returned_count':len(ranked),'request_success':bool(result.get('success')),'recall_at_5':float(rank is not None and rank<=5),'recall_at_10':float(rank is not None and rank<=10),'ndcg_at_10':1/np.log2(rank+1) if rank is not None and rank<=10 else 0.,'mrr_at_10':1/rank if rank is not None and rank<=10 else 0.,'top10':ranked[:10],'full_search_seconds':r.get('fullSeconds')})
    metrics={m:float(np.mean([r[m] for r in rows])) for m in ('recall_at_5','recall_at_10','ndcg_at_10','mrr_at_10')}
    report={'artifact':str(path),'artifact_sha256':digest(path),'count':len(rows),'request_errors':sum(not r['request_success'] for r in rows),'metrics':metrics,'languages':{l:{m:float(np.mean([r[m] for r in rows if r['language']==l])) for m in metrics} for l in sorted({r['language'] for r in rows})},'module_manifest_signatures':sorted(modules),'full_search_seconds_median':float(np.median(timings)) if timings else None,'full_search_seconds_p95':float(np.quantile(timings,.95)) if timings else None,'timing_meaning':'Actual full SearchForAssistant call, not assistant TTFT; possibly concurrent preparation load','limits':['Automatic exact-native-span relevance labels','Five selection families;72of80inonefamily','End-to-end semantic answer correctness not measured','MRR@10 is truncated; unreturned gold contributes0']}
    return rows,report

def main():
    p=argparse.ArgumentParser();p.add_argument('--out',type=pathlib.Path,default=ROOT/'src/build/research-20261024/native-pipeline-comparison');p.add_argument('--untrained',type=pathlib.Path,help='Optional matched native hybrid run with foundation E5, same frozen80 questions');a=p.parse_args()
    examples=read_jsonl(DEFAULT_OUT/'selection.jsonl')
    paths={'lexical':ROOT/'src/build/research-20261024/rag-runtime-lexical/selection.jsonl','hybrid':ROOT/'src/build/research-20261024/rag-runtime-hybrid/selection.jsonl','hybrid_bge8':ROOT/'src/build/research-20261024/rag-runtime-v1/hybrid-bge8-selection.jsonl'}
    if a.untrained:paths['untrained_hybrid']=a.untrained.resolve()
    runs={};reports={};a.out.mkdir(parents=True,exist_ok=True)
    for name,path in paths.items():runs[name],reports[name]=evaluate(path,examples);write_jsonl(a.out/(name+'-metrics-rows.jsonl'),runs[name])
    pairs=[('lexical','hybrid'),('lexical','hybrid_bge8'),('hybrid','hybrid_bge8')]
    if a.untrained:pairs.extend([('lexical','untrained_hybrid'),('untrained_hybrid','hybrid')])
    comparisons={f'{aa}_to_{b}':{m:interval(runs[aa],runs[b],m) for m in ('recall_at_10','ndcg_at_10')} for aa,b in pairs}
    allmodules=set(s for r in reports.values() for s in r['module_manifest_signatures'])
    result={'runs':reports,'paired_family_bootstrap_95':comparisons,'all_runs_same_module_manifest':len(allmodules)==1,'selection_sha256':digest(DEFAULT_OUT/'selection.jsonl'),'final_read':False,'independent_human_review':False,'promotion_basis':'Prefer actual-pipeline positive nDCG CI with no Recall regression and acceptable native latency; raw dense-only results are a separate component experiment','semantic_quality':'unknown outside literal native-span rubric'}
    write_json(a.out/'comparison.json',result);print(json.dumps(result,indent=2))
if __name__=='__main__':main()
