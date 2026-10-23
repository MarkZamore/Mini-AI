"""Five frozen development prompts, fresh process per case, no disk-cache manipulation."""
import argparse,hashlib,json,os,pathlib,queue,subprocess,threading,time,psutil
p=argparse.ArgumentParser();p.add_argument("backend",choices=["cpu","cuda"]);p.add_argument("--deadline",type=int,default=120);args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]/"build/research-20261024";art=root/"gpu-worker"
source=root/"generator/data/generation-messages.jsonl";all_rows=[json.loads(s) for s in source.read_text(encoding="utf-8").splitlines() if s]
indices=[0,3,8,11,15];rows=[all_rows[i] for i in indices]
assert len(rows)==5 and all(x["split"]=="selection" and not x["final_read"] for x in rows)
directory=art/("a1-cpu/model" if args.backend=="cpu" else "a2-4b/model");manifest=json.loads((directory/"worker.json").read_text(encoding="utf-8-sig"))
lease=root/"gpu.lock";owner={"pid":os.getpid(),"owner":"gpu-worker-A1","task":"fresh process CUDA latency five fixed selection prompts"};held=False
if args.backend=="cuda":
    fd=os.open(lease,os.O_CREAT|os.O_EXCL|os.O_WRONLY);os.write(fd,json.dumps(owner).encode());os.close(fd);held=True
reports=[]
try:
    for row in rows:
        before=time.perf_counter();proc=subprocess.Popen([str(directory/"inference-worker.exe"),"--stdio"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding="utf-8",errors="replace",bufsize=1)
        frames=queue.Queue();errors=[]
        def stdout_reader():
            try:
                for line in proc.stdout:frames.put((time.perf_counter(),json.loads(line)))
            finally:frames.put((time.perf_counter(),None))
        def stderr_reader():
            for line in proc.stderr:errors.append(line)
        out=threading.Thread(target=stdout_reader,daemon=True);err=threading.Thread(target=stderr_reader,daemon=True);out.start();err.start()
        def write(frame):proc.stdin.write(json.dumps(frame,ensure_ascii=False)+"\n");proc.stdin.flush()
        report={"id":row["id"],"backend":args.backend,"language":row["language"],"source_ids":row["source_ids"],"max_tokens":row["max_tokens"],"weights":manifest["weights"],"contextTokens":manifest["contextTokens"],"threads":manifest["threads"],"answer":"","censored":False,"success":False,"ttft_seconds":None}
        try:
            write({"type":"hello","protocol":"enterpriseai.inference.v1"});ready_at,frame=frames.get(timeout=args.deadline)
            if not frame or frame.get("type")!="ready":raise RuntimeError(str(frame))
            report["startup_seconds"]=ready_at-before;started=time.perf_counter();deadline=started+args.deadline
            write({"type":"generate","requestId":row["id"],"messages":row["messages"],"maxTokens":row["max_tokens"]})
            while True:
                ts,frame=frames.get(timeout=max(0,deadline-time.perf_counter()))
                if not frame or frame.get("requestId")!=row["id"]:raise RuntimeError(str(frame))
                if frame["type"]=="token":
                    if frame["text"] and report["ttft_seconds"] is None:report["ttft_seconds"]=ts-started
                    report["answer"]+=frame["text"]
                elif frame["type"]=="done":report.update(success=True,generation_seconds=ts-started,total_seconds=ts-before);break
                else:raise RuntimeError(str(frame))
            memory=psutil.Process(proc.pid).memory_info();report.update(peak_worker_rss_bytes=getattr(memory,"peak_wset",memory.rss),peak_worker_commit_bytes=getattr(memory,"peak_pagefile",memory.vms))
        except queue.Empty:
            report.update(censored=True,error="deadline",elapsed_seconds=time.perf_counter()-before,deadline_seconds=args.deadline)
        except Exception as exc:report.update(error=str(exc),elapsed_seconds=time.perf_counter()-before)
        finally:
            if proc.poll() is None:proc.stdin.close()
            try:proc.wait(timeout=3)
            except subprocess.TimeoutExpired:proc.kill();proc.wait()
            out.join(timeout=2);err.join(timeout=2)
            for line in errors:
                if "MINI_AI_USAGE " in line:report["usage"]=json.loads(line.split("MINI_AI_USAGE ",1)[1])
            (art/("a1-"+args.backend+"-"+row["id"][:12]+".stderr.log")).write_text("".join(errors),encoding="utf-8")
            reports.append(report);(art/("a1-"+args.backend+".json")).write_text(json.dumps({"cases":reports,"indices":indices,"input_sha256":hashlib.sha256(source.read_bytes()).hexdigest(),"scope":"same five fixed oracle selection prompts; fresh process for each case; filesystem cache NOT cleared; Teams closed; no final/demo queries; no UI or retrieval latency","censored_values_are_not_complete_latencies":True},ensure_ascii=False,indent=2),encoding="utf-8")
            print(args.backend,len(reports),report["success"],report.get("generation_seconds"),flush=True)
finally:
    if held and json.loads(lease.read_text())["pid"]==os.getpid():lease.unlink()
