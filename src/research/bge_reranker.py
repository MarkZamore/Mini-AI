"""Pinned frozen BGE reranker: download, CPU ONNX export/parity, no training."""
from __future__ import annotations
import argparse,json,pathlib,shutil
import numpy as np
import torch
from huggingface_hub import HfApi,snapshot_download
from transformers import AutoTokenizer,AutoModelForSequenceClassification
from data_pipeline import ROOT,digest,write_json

OUT=ROOT/'src/build/research-20261024/models/bge'
REPO='BAAI/bge-reranker-v2-m3'

def download():
    OUT.mkdir(parents=True,exist_ok=True);lock=OUT/'foundation-lock.json'
    if lock.exists():metadata=json.loads(lock.read_text())
    else:
        info=HfApi().model_info(REPO,revision='953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e')
        metadata={'repo_id':REPO,'revision':info.sha,'source':'https://huggingface.co/BAAI/bge-reranker-v2-m3','license':'apache-2.0','trained':False};write_json(lock,metadata)
    snapshot_download(REPO,revision=metadata['revision'],local_dir=OUT/'base',allow_patterns=['config.json','model.safetensors','model.safetensors.index.json','model-*.safetensors','tokenizer.json','tokenizer_config.json','special_tokens_map.json','sentencepiece.bpe.model','README.md','LICENSE'])
    metadata['files']={p.name:{'bytes':p.stat().st_size,'sha256':digest(p)} for p in (OUT/'base').iterdir() if p.is_file()};write_json(lock,metadata);print(json.dumps(metadata,indent=2))

class Reranker(torch.nn.Module):
    def __init__(self,model):super().__init__();self.model=model
    def forward(self,input_ids,attention_mask,token_type_ids):
        return self.model(input_ids=input_ids,attention_mask=attention_mask,token_type_ids=token_type_ids).logits.float().view(-1)

def export():
    import onnxruntime as ort
    out=OUT/'onnx';out.mkdir(parents=True,exist_ok=True);torch.set_num_threads(4)
    model=Reranker(AutoModelForSequenceClassification.from_pretrained(OUT/'base',local_files_only=True,attn_implementation='eager')).eval()
    tokenizer=AutoTokenizer.from_pretrained(OUT/'base',local_files_only=True)
    examples=[['Какое условие предусмотрено документом?','В документе указано условие участия.'],['Құжатта қандай талап берілген?','Құжатта талаптар көрсетілген.']]
    bb=tokenizer(examples,padding=True,truncation=True,max_length=256,return_tensors='pt')
    if 'token_type_ids' not in bb:bb['token_type_ids']=torch.zeros_like(bb['input_ids'])
    keys=('input_ids','attention_mask','token_type_ids');path=out/'reranker.onnx'
    # Legacy exporter requires str, not pathlib.Path, to enable >2GiB external
    # initializer files. Otherwise it cannot discover the output directory.
    torch.onnx.export(model,tuple(bb[k] for k in keys),str(path),input_names=list(keys),output_names=['scores'],dynamic_axes={k:{0:'batch',1:'sequence'} for k in keys}|{'scores':{0:'batch'}},opset_version=17,dynamo=False,external_data=True)
    opts=ort.SessionOptions();opts.intra_op_num_threads=4;opts.inter_op_num_threads=1
    session=ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider'])
    runtimekeys={v.name for v in session.get_inputs()};parity=[]
    for pairs in [examples,examples[:1],[['договор','условие '*160],['талап','дерек']]]:
        x=tokenizer(pairs,padding=True,truncation=True,max_length=256,return_tensors='pt')
        if 'token_type_ids' not in x:x['token_type_ids']=torch.zeros_like(x['input_ids'])
        with torch.no_grad():expected=model(*(x[k] for k in keys)).numpy()
        actual=session.run(['scores'],{k:x[k].numpy() for k in keys if k in runtimekeys})[0]
        delta=float(np.max(np.abs(actual-expected)));assert delta<.002,(delta,expected,actual)
        parity.append({'shape':list(x['input_ids'].shape),'max_abs':delta,'scores':actual.tolist()})
    tokenizer.save_pretrained(out/'tokenizer')
    sp=OUT/'base/sentencepiece.bpe.model'
    if sp.exists():shutil.copy2(sp,out/'tokenizer/sentencepiece.bpe.model')
    for name in ('tokenizer.json','tokenizer_config.json','special_tokens_map.json'):
        shutil.copy2(OUT/'base'/name,out/'tokenizer'/name)
    manifest={'schemaVersion':1,'repo_id':REPO,'revision':json.loads((OUT/'foundation-lock.json').read_text())['revision'],'license':'apache-2.0','weights_trained':False,'inputs':{k:'int64 [batch,sequence]' for k in sorted(runtimekeys)},'output':{'scores':'float32 [batch], raw relevance logits; not truth probabilities'},'max_length':256,'pair_format':'<s>query</s></s>passage</s> via original XLM-R tokenizer','parity_cases':parity,'parity_passed':True,'onnx_files':{p.name:{'bytes':p.stat().st_size,'sha256':digest(p)} for p in out.iterdir() if p.is_file()},'sentencepiece_sha256':digest(out/'tokenizer/sentencepiece.bpe.model') if sp.exists() else None,'source':'https://huggingface.co/BAAI/bge-reranker-v2-m3','final_used':False}
    write_json(out/'export-manifest.json',manifest);print(json.dumps(manifest,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('command',choices=['download','export']);a=p.parse_args()
    download() if a.command=='download' else export()
