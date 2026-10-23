"""Numerical/tokenizer parity against the actual standalone evidence worker."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import threading
import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--selection', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    root = args.package.resolve()
    tokenizer = AutoTokenizer.from_pretrained(root / 'evidence/tokenizers/embedder', local_files_only=True)
    rows = [json.loads(line) for line in args.selection.read_text(encoding='utf-8').splitlines() if line]
    texts = ['query: ' + row['question'] for row in rows[:80]]
    texts += ['query: '+s for s in ['Қазақша шарттар және мерзімдер', 'Условия, сроки и исключения.', 'A\u00a0B\tC\nD',
        'е\u0308 й\u0306', 'Құжат № 123/2026', 'Лист A1:B5', '中文 English Қазақша Русский', '🙂 🚀',
        '  пробелы   внутри  ', '<s> правило </s>', '<mask> условие', '<unk> исключение', 'a\u200bb',
        'тест '*1000, 'x', '1.25 2,67 2026-10-24', '«цитата» — условие', 'ШАРТТАР', 'обязанности', '']]
    process = subprocess.Popen([str(root/'inference-worker.exe'), '--stdio'], cwd=root, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=subprocess.CREATE_NO_WINDOW)
    frames = queue.Queue()
    errors = bytearray()
    def read():
        try:
            while line := process.stdout.readline(8*1024*1024+1):
                if len(line)>8*1024*1024:raise ValueError('Oversized worker frame')
                frames.put(json.loads(line))
        except BaseException as error:frames.put(error)
    def stderr():
        while chunk := process.stderr.read(4096):
            errors.extend(chunk[:max(0,65536-len(errors))])
    threading.Thread(target=read, daemon=True).start()
    threading.Thread(target=stderr, daemon=True).start()
    def exchange(request):
        process.stdin.write((json.dumps(request,ensure_ascii=False)+'\n').encode());process.stdin.flush()
        frame=frames.get(timeout=180)
        if isinstance(frame, BaseException):raise frame
        if frame.get('type')=='error':raise RuntimeError(frame)
        return frame
    report={'scope':'Native tokenizer and embedding parity; not semantic quality or GUI evaluation', 'cases':len(texts), 'mismatches':[]}
    try:
        ready=exchange({'type':'hello','protocol':'enterpriseai.evidence.v1'})
        assert ready=={'type':'ready','protocol':'enterpriseai.evidence.v1'},ready
        maximum_error=0.;minimum_cosine=1.
        options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
        reference=ort.InferenceSession(str(root/'evidence/encoder.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
        for index,text in enumerate(texts):
            actual=exchange({'type':'tokenize','requestId':f't{index}','text':text,'pair':'','reranker':False})['result']['ids']
            expected=tokenizer(text,truncation=True,max_length=256)['input_ids']
            if actual!=expected:report['mismatches'].append({'case':index,'actual':actual[:30],'expected':expected[:30]})
            if index<20:
                vector=np.asarray(exchange({'type':'embed','requestId':f'e{index}','texts':[text.removeprefix('query: ')],'queries':True})['result']['vectors'][0],dtype=np.float32)
                inputs=tokenizer(text,truncation=True,max_length=256,return_tensors='np')
                feed={i.name:inputs.get(i.name,np.zeros_like(inputs['input_ids'])).astype(np.int64) for i in reference.get_inputs()}
                gold=reference.run(None,feed)[0][0]
                maximum_error=max(maximum_error,float(np.max(np.abs(vector-gold))))
                minimum_cosine=min(minimum_cosine,float(np.dot(vector,gold)/np.linalg.norm(vector)/np.linalg.norm(gold)))
        report.update(max_abs_embedding_error=maximum_error,min_embedding_cosine=minimum_cosine,
            tokenizer_parity_passed=not report['mismatches'],embedding_parity_passed=maximum_error<1e-4 and minimum_cosine>=.9999)
        report['passed']=report['tokenizer_parity_passed'] and report['embedding_parity_passed']
    except BaseException as error:report.update(passed=False,error=repr(error))
    finally:
        process.kill();process.wait();report['stderr_tail']=errors.decode('utf-8',errors='replace')[-3000:]
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('mismatches','stderr_tail')},ensure_ascii=False))
    if not report['passed']:raise SystemExit(1)


if __name__=='__main__':main()
