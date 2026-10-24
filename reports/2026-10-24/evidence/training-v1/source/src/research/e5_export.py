"""Export exact masked mean/L2 E5 pooling and verify CPU ORT parity."""
import argparse, json, pathlib, shutil
import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoModel,AutoTokenizer
from e5_train import BASE,MODELS,pooled
from data_pipeline import digest,write_json

class Embedding(torch.nn.Module):
    def __init__(self,encoder):super().__init__();self.encoder=encoder
    def forward(self,input_ids,attention_mask,token_type_ids):
        return pooled(self.encoder(input_ids=input_ids,attention_mask=attention_mask,token_type_ids=token_type_ids),attention_mask)

def main():
    p=argparse.ArgumentParser();p.add_argument('--variant',choices=['baseline','ordinary','hard','ordinary-seed2'],default='baseline');a=p.parse_args()
    source=BASE if a.variant=='baseline' else MODELS/a.variant/'encoder'
    out=MODELS/a.variant/'onnx';out.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(4);torch.manual_seed(20261024)
    tok=AutoTokenizer.from_pretrained(source,local_files_only=True)
    model=Embedding(AutoModel.from_pretrained(source,local_files_only=True,attn_implementation='eager')).eval()
    texts=['query: Какие условия предусмотрены документом?','passage: Құжатта шарттар мен міндеттер көрсетілген.','query: source citation']
    batch=tok(texts,padding=True,truncation=True,max_length=256,return_tensors='pt')
    if 'token_type_ids' not in batch:batch['token_type_ids']=torch.zeros_like(batch['input_ids'])
    inputs=tuple(batch[k] for k in ('input_ids','attention_mask','token_type_ids'))
    path=out/'encoder.onnx'
    torch.onnx.export(model,inputs,path,input_names=['input_ids','attention_mask','token_type_ids'],output_names=['embedding'],dynamic_axes={k:{0:'batch',1:'sequence'} for k in ('input_ids','attention_mask','token_type_ids')}|{'embedding':{0:'batch'}},opset_version=17,dynamo=False,external_data=False)
    opts=ort.SessionOptions();opts.intra_op_num_threads=4;opts.inter_op_num_threads=1
    session=ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider'])
    cases=[texts,['query: источник'],['passage: шарт '*160,'query: условия'],'query: x']
    parity=[]
    for case in cases:
        if isinstance(case,str):case=[case]
        bb=tok(case,padding=True,truncation=True,max_length=256,return_tensors='pt')
        if 'token_type_ids' not in bb:bb['token_type_ids']=torch.zeros_like(bb['input_ids'])
        ii=tuple(bb[k] for k in ('input_ids','attention_mask','token_type_ids'))
        with torch.no_grad():expected=model(*ii).numpy()
        actual=session.run(['embedding'],{k:bb[k].numpy() for k in ('input_ids','attention_mask','token_type_ids')})[0]
        delta=float(np.max(np.abs(actual-expected)));cos=np.sum(actual*expected,axis=1)
        assert delta<2e-4 and float(cos.min())>.9999,(delta,cos)
        parity.append({'shape':list(bb['input_ids'].shape),'max_abs':delta,'min_cosine':float(cos.min())})
    tok.save_pretrained(out/'tokenizer')
    shutil.copy2(BASE/'sentencepiece.bpe.model',out/'tokenizer/sentencepiece.bpe.model')
    for name in ('tokenizer.json','tokenizer_config.json','special_tokens_map.json'):
        shutil.copy2(BASE/name,out/'tokenizer'/name)
    report={'variant':a.variant,'onnx_sha256':digest(path),'onnx_size':path.stat().st_size,'schemaVersion':1,'inputs':{k:'int64 [batch,sequence]' for k in ('input_ids','attention_mask','token_type_ids')},'output':{'embedding':'float32 [batch,384] masked mean pooled, L2 normalised'},'max_length':256,'query_prefix':'query: ','passage_prefix':'passage: ','parity_max_abs':max(p['max_abs'] for p in parity),'parity_min_cosine':min(p['min_cosine'] for p in parity),'parity_cases':parity,'parity_passed':True,'ort_version':ort.__version__,'torch_version':torch.__version__,'final_used':False}
    report['sentencepiece_sha256']=digest(out/'tokenizer/sentencepiece.bpe.model')
    write_json(out/'export-manifest.json',report);print(json.dumps(report,indent=2))
if __name__=='__main__':main()
