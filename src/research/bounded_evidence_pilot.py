"""Learn ONLY whether a specified literal atom is fully provided in context.

The label does not assert semantic sufficiency, answer truth, or corpus-wide
absence. No gold-overlap, source IDs or truncation flags enter model features.
"""
import collections,json,pathlib
import numpy as np
from transformers import AutoTokenizer
from data_pipeline import ROOT,DEFAULT_OUT,read_jsonl,write_json,write_jsonl,digest
from adequacy_stress import session,inputs
from e5_train import MODELS

def main():
    variant='ordinary';ix=ROOT/f'src/build/research-20261024/rag-index-{variant}';im=json.loads((ix/'index.json').read_text())
    metadata=read_jsonl(ix/'fragments.jsonl');positions={r['fragment_id']:i for i,r in enumerate(metadata)};vectors=np.memmap(ix/'vectors.f32',dtype='<f4',mode='r',shape=(im['rows'],384))
    sources={s['fragment_id']:s for s in read_jsonl(DEFAULT_OUT/'sources.jsonl')}
    tokenizer=AutoTokenizer.from_pretrained(MODELS/variant/'onnx/tokenizer',local_files_only=True);encoder=session(MODELS/variant/'onnx/encoder.onnx')
    query_cache={};partial_cache={};datasets={}
    for split in ('train','selection','calibration','gatecheck'):
        examples=read_jsonl(DEFAULT_OUT/(split+'.jsonl'));rows=[]
        for e in examples:
            s=sources[e['positive_ids'][0]];question=e['question']
            if question not in query_cache:query_cache[question]=encoder.run(['embedding'],inputs(tokenizer,['query: '+question]))[0][0]
            other=next((sources[r['positive_ids'][0]] for r in examples if r['family_ids']!=e['family_ids']),None)
            cases=[('full',[(s['text'],np.asarray(vectors[positions[s['fragment_id']]]))],1)]
            if other:cases.append(('redundant',[(s['text'],np.asarray(vectors[positions[s['fragment_id']]])),(other['text'],np.asarray(vectors[positions[other['fragment_id']]]))],1))
            if len(s['text'])>=160:
                partial=s['text'][:len(s['text'])//2].rsplit(' ',1)[0]
                if partial not in partial_cache:partial_cache[partial]=encoder.run(['embedding'],inputs(tokenizer,['passage: '+partial]))[0][0]
                cases.append(('partial',[(partial,partial_cache[partial])],0))
            for context_kind,contexts,label in cases:
                scores=[float(query_cache[question]@v) for _,v in contexts];length=sum(len(t) for t,_ in contexts)
                feature=[max(scores),min(scores),float(np.mean(scores)),length/2000.,len(contexts)/3.]
                assert bool(s['text'] in '\n'.join(t for t,_ in contexts))==bool(label)
                rows.append({'id':e['id']+':'+context_kind,'intent_group':e['intent_group'],'families':e['family_ids'],'context_kind':context_kind,'features':feature,'label_literal_atom_fully_present':label,'semantic_sufficiency':'unknown'})
        datasets[split]=rows
    train=datasets['train'];x=np.asarray([r['features'] for r in train]);y=np.asarray([r['label_literal_atom_fully_present'] for r in train],dtype=float)
    mean=x.mean(axis=0);std=x.std(axis=0);std[std<1e-6]=1.;z=np.c_[(x-mean)/std,np.ones(len(x))];weights=np.zeros(z.shape[1]);class_weight=np.where(y==1,len(y)/(2*y.sum()),len(y)/(2*(1-y).sum()))
    for step in range(1000):
        prob=1/(1+np.exp(-np.clip(z@weights,-30,30)));gradient=z.T@((prob-y)*class_weight)/len(y)+.01*np.r_[weights[:-1],0.]
        weights-=.1*gradient
    def metric(labels,prediction):
        tp=int(np.sum((labels==1)&(prediction==1)));tn=int(np.sum((labels==0)&(prediction==0)));fp=int(np.sum((labels==0)&(prediction==1)));fn=int(np.sum((labels==1)&(prediction==0)))
        return {'tp':tp,'tn':tn,'fp_incomplete_context_marked_complete':fp,'fn_complete_context_marked_incomplete':fn,'accuracy':(tp+tn)/len(labels),'balanced_accuracy':.5*(tp/max(1,tp+fn)+tn/max(1,tn+fp)),'complete_coverage':tp/max(1,tp+fn),'false_complete_rate':fp/max(1,fp+tn)}
    scores={}
    for split,rows in datasets.items():
        xx=np.asarray([r['features'] for r in rows]);yy=np.asarray([r['label_literal_atom_fully_present'] for r in rows]);zz=np.c_[(xx-mean)/std,np.ones(len(xx))];pp=1/(1+np.exp(-np.clip(zz@weights,-30,30)))
        scores[split]={'examples':len(rows),'intent_groups':len({r['intent_group'] for r in rows}),'families':len({f for r in rows for f in r['families']}),'context_variants':dict(collections.Counter(r['context_kind'] for r in rows)),'any_nonempty_context_control':metric(yy,np.ones(len(yy),dtype=int)),'logistic_literal_presence_pilot':metric(yy,(pp>=.5).astype(int))}
    out=ROOT/'src/build/research-20261024/bounded-evidence-pilot';out.mkdir(exist_ok=True)
    write_json(out/'model.json',{'task':'literal_gold_atom_fully_present_in_provided_context_ONLY','feature_names':['max_e5_similarity','min_e5_similarity','mean_e5_similarity','context_characters_div_2000','context_count_div_3'],'standardization_mean':mean.tolist(),'standardization_std':std.tolist(),'weights_and_intercept':weights.tolist(),'optimizer':'Full-batch gradient descent,1000steps,lr.1,L2.01,class-balanced binary crossentropy','threshold_fixed_before_development':.5,'probability_meaning':'Uncalibrated binary-task score; NOT probability of answer truth','production_accepted':False,'human_review':False,'final_read':False})
    report={'metrics':scores,'train_sha256':digest(DEFAULT_OUT/'train.jsonl'),'feature_embedder_variant':variant,'human_review':False,'final_read':False,'production_accepted':False,'claims':'Auxiliary provided-context literal completeness only, no semantic sufficiency or corpus-wide no-answer conclusion','limitations':['Automatic truncation construction, few partial negatives','Length features can learn truncation artifacts','Full gold atom is an extractive paragraph, not independently complete normative gold','Threshold was fixed .5; no tuning on these development results']}
    write_json(out/'metrics.json',report);print(json.dumps(report,indent=2))
if __name__=='__main__':main()
