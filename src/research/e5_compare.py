"""Paired family bootstrap on development only; no final-test reader."""
import argparse,collections,json
import numpy as np
from data_pipeline import ROOT,SEED,read_jsonl,write_json
from e5_train import MODELS

def interval(left,right,metric):
    a={r['id']:r for r in left};b={r['id']:r for r in right};assert a.keys()==b.keys()
    groups=collections.defaultdict(list)
    for k in a:groups[a[k]['family_id']].append(float(b[k][metric]-a[k][metric]))
    keys=sorted(groups);rng=np.random.default_rng(SEED);values=[]
    for _ in range(10000):
        chosen=rng.choice(keys,size=len(keys),replace=True)
        values.append(np.mean([v for g in chosen for v in groups[g]]))
    lo,hi=np.quantile(values,[.025,.975]);return {'difference':float(np.mean([b[k][metric]-a[k][metric] for k in a])),'ci95_family_bootstrap':[float(lo),float(hi)],'positive_interval':bool(lo>0),'families':len(keys)}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--pool',choices=['pilot','full'],default='pilot');parser.add_argument('--seed2',action='store_true');args=parser.parse_args()
    names=('baseline','ordinary','hard') if args.pool=='pilot' else ('baseline','ordinary')
    if args.seed2:names+=('ordinary-seed2',)
    rows={name:read_jsonl(MODELS/name/'retrieval-results.jsonl' if args.pool=='pilot' else ROOT/f'src/build/research-20261024/rag-index-{name}/full-retrieval-results.jsonl') for name in names}
    metrics={name:{m:float(np.mean([r[m] for r in rr])) for m in ('recall_at_5','recall_at_10','ndcg_at_10','mrr')} for name,rr in rows.items()}
    pairs=[('baseline','ordinary'),('baseline','hard'),('ordinary','hard')] if args.pool=='pilot' else [('baseline','ordinary')]
    if args.seed2:pairs.extend([('baseline','ordinary-seed2'),('ordinary','ordinary-seed2')])
    comparisons={f'{a}_to_{b}':{m:interval(rows[a],rows[b],m) for m in ('recall_at_10','ndcg_at_10')} for a,b in pairs}
    result={'metrics':metrics,'comparisons':comparisons,'final_read':False,'selection_examples':80,'selection_families':5,'warning':'One family has 72 of 80 questions; bootstrap cannot repair narrow source coverage','training_contribution_scope':'117653760 full encoder parameters; document-qualified extraction only; not end-to-end answer accuracy','selection_decision':'Root must apply pre-agreed gates; this comparison does not authorize production or open final'}
    result['candidate_pool']=564 if args.pool=='pilot' else 106139
    if args.seed2:
        result['replication']={'seeds':[20261024,20261025],'same_data_hyperparameters':True,'seed_population_uncertainty':'Only two seeds; not a seed-population confidence estimate','production_candidate_fixed_seed':20261024,'no_best_seed_selection':True}
    write_json(MODELS/('comparison.json' if args.pool=='pilot' else 'full-comparison.json'),result);print(json.dumps(result,indent=2))
if __name__=='__main__':main()
