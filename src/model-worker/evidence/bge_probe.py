"""Original tokenizer / ONNX parity against the shipped native CPU reranker.

Reference and worker sessions are loaded sequentially to bound physical RAM.
This validates interoperability; it does not validate relevance or truth.
"""
import argparse
import gc
import json
from pathlib import Path
import queue
import subprocess
import threading
import time
import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--package',type=Path,required=True)
    p.add_argument('--selection',type=Path,required=True)
    p.add_argument('--sources',type=Path,required=True)
    p.add_argument('--report',type=Path,required=True)
    args=p.parse_args();root=args.package.resolve()
    rows=[json.loads(s) for s in args.selection.read_text(encoding='utf-8').splitlines() if s]
    sources={r['fragment_id']:r for r in map(json.loads,args.sources.read_text(encoding='utf-8').splitlines())}
    cases=[(r['question'],sources[r['positive_ids'][0]]['text']) for r in rows[:80]]
    cases += [(s,'Условия ҚАЗАҚША Русский '+s) for s in ['<s> условие </s>','<mask> мәтін','<unk> исключение',
        'A\u00a0B\tC\nD','е\u0308 й\u0306','🙂 🚀','中文 Құжат','  пробелы  ',
        'тест '*1000,'a\u200bb','', '№ 123/2026','1.25, 2,67','Лист A1:B5',
        'ШАРТТАР','сроки','<pad> текст','<s></s>','құқықтар', '«цитата» — условие']]
    tokenizer=AutoTokenizer.from_pretrained(root/'evidence/tokenizers/reranker',local_files_only=True)
    encoded=[tokenizer(a,b,truncation=True,max_length=256,return_tensors='np') for a,b in cases]
    options=ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1
    reference=ort.InferenceSession(str(root/'evidence/reranker/reranker.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
    gold={}
    for i in list(range(16))+[82,88,91,98]:
        feed={x.name:encoded[i].get(x.name,np.zeros_like(encoded[i]['input_ids'])).astype(np.int64) for x in reference.get_inputs()}
        gold[i]=float(np.asarray(reference.run(None,feed)[0]).reshape(-1)[0])
    del reference;gc.collect()
    process=subprocess.Popen([str(root/'inference-worker.exe'),'--stdio'],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,creationflags=subprocess.CREATE_NO_WINDOW)
    frames=queue.Queue();errors=bytearray()
    def reader():
        try:
            while line:=process.stdout.readline(8*1024*1024+1):
                if len(line)>8*1024*1024:raise ValueError('Oversized frame')
                frames.put(json.loads(line))
        except BaseException as error:frames.put(error)
    def stderr():
        while block:=process.stderr.read(4096):errors.extend(block[:max(0,65536-len(errors))])
    threading.Thread(target=reader,daemon=True).start();threading.Thread(target=stderr,daemon=True).start()
    def exchange(value):
        process.stdin.write((json.dumps(value,ensure_ascii=False)+'\n').encode());process.stdin.flush()
        result=frames.get(timeout=180)
        if isinstance(result,BaseException):raise result
        if result.get('type')=='error':raise RuntimeError(result)
        return result
    report={'scope':'Native BGE tokenizer/logit parity; not semantic quality','cases':len(cases),'mismatches':[],'logit_cases':[]}
    try:
        assert exchange({'type':'hello','protocol':'enterpriseai.evidence.v1'})['type']=='ready'
        for i,(a,b) in enumerate(cases):
            actual=exchange({'type':'tokenize','requestId':f't{i}','text':a,'pair':b,'reranker':True})['result']['ids']
            expected=encoded[i]['input_ids'][0].tolist()
            if actual!=expected:report['mismatches'].append({'case':i,'actual':actual[:32],'expected':expected[:32]})
            if i in gold:
                started=time.perf_counter()
                logit=exchange({'type':'rerank','requestId':f'r{i}','query':a,'candidates':[{'fragmentId':f'parity-{i}','documentId':'parity','text':b}]})['result']['hits'][0]['score']
                report['logit_cases'].append({'case':i,'reference':gold[i],'native':logit,'abs_error':abs(logit-gold[i]),'seconds':time.perf_counter()-started})
        report['max_abs_logit_error']=max(r['abs_error'] for r in report['logit_cases'])
        report['passed']=not report['mismatches'] and report['max_abs_logit_error']<5e-4
    except BaseException as error:report.update(passed=False,error=repr(error))
    finally:
        process.kill();process.wait();report['stderr_tail']=errors.decode('utf-8',errors='replace')[-3000:]
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('mismatches','logit_cases','stderr_tail')},ensure_ascii=False))
    if not report['passed']:raise SystemExit(1)


if __name__=='__main__':main()
