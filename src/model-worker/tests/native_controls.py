"""Real package rejection, CUDA budget, cancellation and subsequent request. No corpus questions."""
import json, os, pathlib, queue, subprocess, threading, time
root=pathlib.Path(__file__).resolve().parents[2]/"build/research-20261024"
art=root/"gpu-worker";source=art/"package/model";lock=root/"gpu.lock"
owner={"pid":os.getpid(),"owner":"gpu-worker-controls","task":"native package/cancel checks"}
try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
except FileExistsError:print("GPU_BUSY "+lock.read_text());raise SystemExit(3)
os.write(fd,json.dumps(owner).encode());os.close(fd)
reports=[]
class Session:
    def __init__(self,model):
        self.p=subprocess.Popen([str(model/"inference-worker.exe"),"--stdio"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding="utf-8",errors="replace",bufsize=1)
        self.q=queue.Queue();self.errors=[]
        def read():
            for line in self.p.stdout:self.q.put(json.loads(line))
            self.q.put(None)
        def diagnostics():
            for line in self.p.stderr:self.errors.append(line)
        threading.Thread(target=read,daemon=True).start();threading.Thread(target=diagnostics,daemon=True).start()
        self.write({"type":"hello","protocol":"enterpriseai.inference.v1"})
    def write(self,value):self.p.stdin.write(json.dumps(value,ensure_ascii=False)+"\n");self.p.stdin.flush()
    def read(self,timeout=120):
        frame=self.q.get(timeout=timeout)
        if frame is None:raise RuntimeError(f"Unexpected EOF {self.p.poll()}")
        return frame
    def close(self):
        if self.p.poll() is None:self.p.stdin.close()
        try:self.p.wait(timeout=5)
        except subprocess.TimeoutExpired:self.p.kill();self.p.wait()
def fixture(name):
    target=art/"control-packages"/name
    for path in source.rglob("*"):
        if path.is_file() and path.name!="worker.json":
            out=target/path.relative_to(source);out.parent.mkdir(parents=True,exist_ok=True)
            if not out.exists():os.link(path,out)
    return target,json.loads((source/"worker.json").read_text(encoding="utf-8-sig"))
try:
    for name,expected in [("wrong-hash","INCOMPATIBLE_MODEL"),("unlisted-dll","MODEL_PACKAGE_NOT_SELF_CONTAINED"),("gpu-budget","OUT_OF_MEMORY")]:
        target,manifest=fixture(name)
        if name=="wrong-hash":
            manifest["weights"]["sha256"]="0"*64
            for item in manifest["files"]:
                if item["path"]==manifest["weights"]["path"]:item["sha256"]="0"*64
        elif name=="unlisted-dll":(target/"runtime/unlisted.dll").write_bytes(b"invalid fixture")
        else:manifest["backend"]["gpuMemoryBudgetBytes"]=32<<30
        (target/"worker.json").write_text(json.dumps(manifest),encoding="utf-8")
        session=Session(target)
        try:
            frame=session.read();assert frame["type"]=="error" and frame["code"]==expected,frame
            reports.append({"case":name,"success":True,"code":frame["code"]})
        finally:session.close()
    session=Session(source)
    try:
        assert session.read()["type"]=="ready"
        session.write({"type":"generate","requestId":"cancel","messages":[{"role":"user","content":"Перечисли все целые числа от 1 до 10000 по порядку."}],"maxTokens":2048})
        while True:
            frame=session.read()
            if frame["type"]=="token" and frame["text"]:break
            if frame["type"]=="error":raise RuntimeError(frame)
        started=time.perf_counter();session.write({"type":"cancel","requestId":"cancel"})
        while True:
            frame=session.read(3)
            if frame["type"]!="token":break
        elapsed=time.perf_counter()-started
        assert frame["type"]=="cancelled" and elapsed<2,(frame,elapsed)
        session.write({"type":"generate","requestId":"recovery","messages":[{"role":"system","content":"Ответь одним числом."},{"role":"user","content":"Сколько будет 2 + 2?"}],"maxTokens":32})
        answer=""
        while True:
            frame=session.read()
            if frame["type"]=="token":answer+=frame["text"]
            elif frame["type"]=="done":break
            else:raise RuntimeError(frame)
        assert answer.strip()=="4",answer
        reports.append({"case":"cancel-and-recovery","success":True,"cancel_seconds":elapsed,"recovery_answer":answer})
    finally:session.close()
finally:
    (art/"native-controls.json").write_text(json.dumps({"scope":"real native transport and rejection; no assistant/corpus quality claim","cases":reports},ensure_ascii=False,indent=2),encoding="utf-8")
    if json.loads(lock.read_text()).get("pid")==owner["pid"]:lock.unlink()
print(json.dumps(reports,ensure_ascii=False))
