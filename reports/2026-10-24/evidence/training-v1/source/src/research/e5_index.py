"""Build the production-format full corpus sidecar only after selection freeze."""
import argparse,json,pathlib,sqlite3,os,math
import numpy as np
import torch
from transformers import AutoModel,AutoTokenizer
from data_pipeline import ROOT,DEFAULT_OUT,digest,write_json,write_jsonl,read_jsonl
from e5_train import BASE,MODELS,encode,gpu_lease

def main():
    p=argparse.ArgumentParser();p.add_argument('--variant',choices=['baseline','ordinary','hard','ordinary-seed2'],required=True);p.add_argument('--selection-freeze',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=ROOT/'src/build/research-20261024/rag-index');p.add_argument('--device',choices=['cpu','cuda'],default='cpu');p.add_argument('--allow-cuda',action='store_true');p.add_argument('--batch-size',type=int,default=32);p.add_argument('--min-score',type=float,default=-1.);a=p.parse_args()
    freeze=json.loads(a.selection_freeze.read_text(encoding='utf-8'))
    assert freeze['e5_variant']==a.variant and freeze['final_opened'] is False,'Candidate identity must be registered before final disclosure'
    if a.device=='cuda' and not a.allow_cuda:raise ValueError('GPU requires --allow-cuda')
    export=MODELS/a.variant/'onnx/encoder.onnx';assert export.exists(),'Export/parity required'
    db=ROOT/'db/index.sqlite';con=sqlite3.connect(db.as_uri()+'?mode=ro&immutable=1',uri=True);con.row_factory=sqlite3.Row
    sections={r['document_id']:json.loads(r['sections']) for r in con.execute('SELECT document_id,sections FROM documents')}
    rows=list(con.execute('SELECT fragment_id,document_id,text FROM chunks ORDER BY fragment_id'));con.close()
    assert len(rows)==106139,'Corpus row count changed; rerun audit before indexing'
    a.out.mkdir(parents=True,exist_ok=True);torch.set_num_threads(4)
    modelpath=BASE if a.variant=='baseline' else MODELS/a.variant/'encoder'
    tok=AutoTokenizer.from_pretrained(modelpath,local_files_only=True)
    with gpu_lease(a.device=='cuda'):
        model=AutoModel.from_pretrained(modelpath,local_files_only=True).to(a.device)
        # Atomic visibility: index.json is published only after vectors+metadata.
        with (a.out/'vectors.f32.partial').open('wb') as f:
            for start in range(0,len(rows),256):
                batch=rows[start:start+256]
                vec=encode(model,tok,['passage: '+r['text'] for r in batch],a.device,max_length=256,batch_size=a.batch_size)
                f.write(vec.numpy().astype('<f4',copy=False).tobytes())
                if start%4096==0:print(json.dumps({'indexed':start+len(batch),'total':len(rows)}),flush=True)
        os.replace(a.out/'vectors.f32.partial',a.out/'vectors.f32')
        # Real candidate-pool validation, not a toy native-only pool. Final
        # question/label files remain sealed; all corpus text may be indexed.
        examples=read_jsonl(DEFAULT_OUT/'selection.jsonl')
        queries=encode(model,tok,['query: '+e['question'] for e in examples],a.device,max_length=256,batch_size=8).numpy()
        vectors=np.memmap(a.out/'vectors.f32',dtype='<f4',mode='r',shape=(len(rows),384))
        positions={r['fragment_id']:i for i,r in enumerate(rows)};validation=[]
        for ex,q in zip(examples,queries):
            score=np.asarray(vectors@q)
            sorted_indices=np.argsort(-score,kind='stable')
            ranks=[int(np.where(sorted_indices==positions[p])[0][0])+1 for p in ex['positive_ids'] if p in positions]
            rank=min(ranks,default=len(rows)+1);top=sorted_indices[:10]
            validation.append({'id':ex['id'],'language':ex['language'],'family_id':ex['family_ids'][0],'recall_at_5':float(rank<=5),'recall_at_10':float(rank<=10),'ndcg_at_10':1/math.log2(rank+1) if rank<=10 else 0.,'mrr':1/rank,'rank':rank,'top10':[rows[i]['fragment_id'] for i in top]})
        metrics={m:float(np.mean([r[m] for r in validation])) for m in ('recall_at_5','recall_at_10','ndcg_at_10','mrr')}
        full_report={'variant':a.variant,'candidate_pool':len(rows),'selection_examples':len(examples),'metrics':metrics,'languages':{l:{m:float(np.mean([r[m] for r in validation if r['language']==l])) for m in metrics} for l in sorted({r['language'] for r in validation})},'selection_sha256':digest(DEFAULT_OUT/'selection.jsonl'),'final_read':False,'limits':['Native automatic labels, no independent semantic gold','Five selection families; one has 72 of 80 questions']}
        write_json(a.out/'full-retrieval-metrics.json',full_report);write_jsonl(a.out/'full-retrieval-results.jsonl',validation)
    write_jsonl(a.out/'fragments.jsonl',[{'fragment_id':r['fragment_id'],'document_id':r['document_id'],'sections':sections[r['document_id']]} for r in rows])
    report={'schemaVersion':1,'baseId':'local','dimensions':384,'rows':len(rows),'corpusSha256':digest(db),'embedderSha256':digest(export),'vectors':'vectors.f32','fragments':'fragments.jsonl','minScore':a.min_score,'minScoreMeaning':'Coverage-only retrieval cutoff; not calibrated probability or evidence sufficiency','normalization':'L2','dtype':'little-endian float32','maxLength':256,'queryPrefix':'query: ','passagePrefix':'passage: ','vectorsSha256':digest(a.out/'vectors.f32'),'fragmentsSha256':digest(a.out/'fragments.jsonl'),'selectionFreezeSha256':digest(a.selection_freeze)}
    write_json(a.out/'index.json',report);print(json.dumps(report,indent=2))
if __name__=='__main__':main()
