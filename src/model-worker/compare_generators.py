"""Paired generation-only candidate pilot on the already frozen selection oracle messages."""
import hashlib,json,os,pathlib,queue,subprocess,threading,time,sys,psutil
root=pathlib.Path(__file__).resolve().parents[1]/"build/research-20261024"
art=root/"gpu-worker";lock=root/"gpu.lock"
stress="--stress" in sys.argv
inputs=root/("generator/data/position-stress-messages.jsonl" if stress else "generator/data/generation-messages.jsonl")
prefix="a2-stress" if stress else "a2-generation"
if "--trained-only" in sys.argv:prefix+="-trained"
rows=[json.loads(line) for line in inputs.read_text(encoding="utf-8").splitlines() if line]
assert len(rows)==16 and all(row["split"]=="selection" and not row["final_read"] for row in rows)
owner={"pid":os.getpid(),"owner":"gpu-worker-A2","task":"paired 4B Q6 /9B Q4 development pilot"}
try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
except FileExistsError:print("GPU_BUSY "+lock.read_text());raise SystemExit(3)
os.write(fd,json.dumps(owner).encode());os.close(fd)
reports=[]
variants=[("4B-Q6_K",art/"a2-4b/model"),("9B-Q4_K_M",art/"a2-9b/model")]
if "--include-trained" in sys.argv:variants.append(("4B-trained-A-Q6_K",art/"a2-trained/model"))
if "--trained-only" in sys.argv:variants=[("4B-trained-A-Q6_K",art/"a2-trained/model")]
try:
    for label,directory in variants:
        manifest=json.loads((directory/"worker.json").read_text(encoding="utf-8-sig"))
        proc=subprocess.Popen([str(directory/"inference-worker.exe"),"--stdio"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding="utf-8",errors="replace",bufsize=1)
        q=queue.Queue();errors=[]
        def reader():
            for line in proc.stdout:q.put((time.perf_counter(),json.loads(line)))
            q.put((time.perf_counter(),None))
        def stderr():
            for line in proc.stderr:errors.append(line)
        out_thread=threading.Thread(target=reader,daemon=True);err_thread=threading.Thread(target=stderr,daemon=True)
        out_thread.start();err_thread.start()
        def write(frame):proc.stdin.write(json.dumps(frame,ensure_ascii=False)+"\n");proc.stdin.flush()
        started=time.perf_counter()
        try:
            write({"type":"hello","protocol":"enterpriseai.inference.v1"})
            ts,frame=q.get(timeout=180)
            if not frame or frame["type"]!="ready":raise RuntimeError(frame)
            startup=ts-started
            for row in rows:
                started=time.perf_counter();first=None;answer="";count=0
                write({"type":"generate","requestId":row["id"],"messages":row["messages"],"maxTokens":row["max_tokens"]})
                while True:
                    ts,frame=q.get(timeout=180)
                    if frame is None:raise RuntimeError(f"EOF {proc.poll()}")
                    if frame.get("requestId")!=row["id"]:raise RuntimeError("Request ID mismatch")
                    if frame["type"]=="token":
                        if frame["text"] and first is None:first=ts
                        answer+=frame["text"];count+=bool(frame["text"])
                    elif frame["type"]=="done":break
                    else:raise RuntimeError(frame)
                memory=psutil.Process(proc.pid).memory_info()
                reports.append({"id":row["id"],"model":label,"language":row["language"],"family_ids":row["family_ids"],"source_ids":row["source_ids"],"answer":answer,"seconds":ts-started,"ttft_seconds":None if first is None else first-started,"nonempty_frames":count,"max_tokens":row["max_tokens"],"temperature":0,"sampler":"greedy","seed":row["seed"],"startup_seconds":startup,"startup_scope":"fresh process, filesystem cache not cleared; later requests reuse the process","peak_worker_rss_bytes":getattr(memory,"peak_wset",memory.rss),"peak_worker_commit_bytes":getattr(memory,"peak_pagefile",memory.vms),"memory_scope":"cumulative process high water marks","weights":manifest["weights"],"contextTokens":manifest["contextTokens"],"split":"selection","contexts":"position stress with two distractors" if stress else "fixed oracle excerpts","positive_alias":row.get("positive_alias","1"),"human_verified":False})
                (art/(prefix+"-results.jsonl")).write_text("".join(json.dumps(item,ensure_ascii=False)+"\n" for item in reports),encoding="utf-8")
                print(f"{label} {len([x for x in reports if x['model']==label])}/16",flush=True)
        finally:
            if proc.poll() is None:proc.stdin.close()
            try:proc.wait(timeout=10)
            except subprocess.TimeoutExpired:proc.kill();proc.wait()
            out_thread.join(timeout=3);err_thread.join(timeout=3)
            for line in errors:
                if "MINI_AI_USAGE " in line:
                    usage=json.loads(line.split("MINI_AI_USAGE ",1)[1])
                    for item in reports:
                        if item["model"]==label and item["id"]==usage["requestId"]:item["usage"]=usage
            (art/(prefix+"-results.jsonl")).write_text("".join(json.dumps(item,ensure_ascii=False)+"\n" for item in reports),encoding="utf-8")
            (art/(prefix+"-"+label+".stderr.log")).write_text("".join(errors),encoding="utf-8")
    summary={"success":True,"cases":16,"models":len(variants),"input_sha256":hashlib.sha256(inputs.read_bytes()).hexdigest(),"scope":"generation-only selection pilot, no retrieval/final/demo quality claim","quantization_and_model_size_both_differ":True,"seed_note":"20261024 fixed in the case manifest; greedy sampler uses no random draws"}
    (art/(prefix+"-summary.json")).write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
finally:
    if json.loads(lock.read_text()).get("pid")==owner["pid"]:lock.unlink()
