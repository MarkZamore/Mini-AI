"""One real native generation under the shared exclusive GPU lease; no corpus/demo questions."""
import json, os, pathlib, queue, subprocess, sys, threading, time

root=pathlib.Path(__file__).resolve().parents[1]/"build/research-20261024"
art=root/"gpu-worker"
lock=root/"gpu.lock"
owner={"pid":os.getpid(),"owner":"gpu-worker","task":"4B CUDA generation transport"}
try:
    fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
except FileExistsError:
    print("GPU_BUSY "+lock.read_text(encoding="utf-8"));sys.exit(3)
os.write(fd,json.dumps(owner).encode());os.close(fd)
report={"scope":"native worker generation, not end-to-end assistant quality","owner":owner}
process=None
try:
    model=art/"package/model"
    started=time.monotonic()
    process=subprocess.Popen([str(model/"inference-worker.exe"),"--stdio"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding="utf-8",errors="replace",bufsize=1)
    q=queue.Queue();errors=[]
    def output():
        for line in process.stdout:q.put((time.monotonic(),line))
        q.put((time.monotonic(),None))
    def diagnostics():
        for line in process.stderr:errors.append(line)
    threading.Thread(target=output,daemon=True).start();threading.Thread(target=diagnostics,daemon=True).start()
    def write(frame):process.stdin.write(json.dumps(frame,ensure_ascii=False)+"\n");process.stdin.flush()
    write({"type":"hello","protocol":"enterpriseai.inference.v1"})
    frames=[];answer="";generated=None;first=None
    while True:
        timestamp,line=q.get(timeout=180)
        if line is None:raise RuntimeError(f"Worker exited {process.poll()}")
        frame=json.loads(line);frames.append(frame)
        if frame["type"]=="error":raise RuntimeError(str(frame))
        if frame["type"]=="ready":
            report["startup_seconds"]=timestamp-started;generated=time.monotonic()
            write({"type":"generate","requestId":"gpu-math-smoke","messages":[{"role":"system","content":"Ответь коротко, одним числом."},{"role":"user","content":"Сколько будет 2 + 2?"}],"maxTokens":48})
        elif frame["type"]=="token":
            if frame["text"] and first is None:first=timestamp
            answer+=frame["text"]
        elif frame["type"]=="done":
            report.update(success=True,answer=answer,seconds=timestamp-started,generation_seconds=timestamp-generated,ttft_seconds=first-generated if first else None,frames=frames)
            break
    process.stdin.close();process.wait(timeout=15)
except BaseException as error:
    report.update(success=False,error=str(error));raise
finally:
    if process and process.poll() is None:process.kill();process.wait()
    if "errors" in locals():
        (art/"gpu-probe.stderr.log").write_text("".join(errors),encoding="utf-8")
        report["cuda_offload_observed"]=any("offload" in line.lower() and "gpu" in line.lower() for line in errors)
    (art/"gpu-probe.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    if json.loads(lock.read_text(encoding="utf-8")).get("pid")==owner["pid"]:lock.unlink()
    print(json.dumps(report,ensure_ascii=False))
