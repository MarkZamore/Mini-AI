"""Explicitly leased native transport checks on a separate full worker package; no corpus quality claim."""
import argparse, json, os, pathlib, queue, re, subprocess, threading, time
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--model',required=True,type=pathlib.Path,help='New separately staged full evidence-capable CUDA model package; never a frozen runtime')
parser.add_argument('--out',required=True,type=pathlib.Path,help='New output directory, including hardlinked rejection fixtures')
options=parser.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]/"build/research-20261024"
art=options.out.resolve();source=options.model.resolve();lock=root/"gpu.lock"
assert source.is_dir() and (source/'worker.json').is_file()
assert json.loads((source/'worker.json').read_text(encoding='utf-8-sig'))['backend']['kind']=='cuda','Use the new full CUDA package for CUDA-budget controls'
assert not art.exists(),'Preserve previous test results; choose a new output directory'
art.mkdir(parents=True)
owner={"pid":os.getpid(),"owner":"gpu-worker-controls","task":"native package, cap, grammar, cancel and subsequent-request controls","model":str(source)}
try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
except FileExistsError:print("GPU_BUSY "+lock.read_text());raise SystemExit(3)
os.write(fd,json.dumps(owner).encode());os.close(fd)
reports=[]
completed=False
def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError('Duplicate JSON key: '+key)
        result[key]=value
    return result
class Session:
    count=0
    def __init__(self,model):
        Session.count+=1;self.number=Session.count;self.model=model
        self.p=subprocess.Popen([str(model/"inference-worker.exe"),"--stdio"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding="utf-8",errors="replace",bufsize=1)
        self.q=queue.Queue();self.errors=[]
        def read():
            for line in self.p.stdout:self.q.put(json.loads(line))
            self.q.put(None)
        def diagnostics():
            for line in self.p.stderr:self.errors.append(line)
        self.output_thread=threading.Thread(target=read,daemon=True);self.output_thread.start()
        self.diagnostics_thread=threading.Thread(target=diagnostics,daemon=True);self.diagnostics_thread.start()
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
        self.diagnostics_thread.join(timeout=2)
        (art/f'session-{self.number:02d}-{self.model.name}-stderr.log').write_text(''.join(self.errors),encoding='utf-8')
    def generated(self,identifier,messages,maximum):
        self.write({'type':'generate','requestId':identifier,'messages':messages,'maxTokens':maximum})
        answer='';frames=[]
        while True:
            frame=self.read();frames.append(frame)
            assert frame.get('requestId')==identifier,frame
            if frame['type']=='token':
                assert set(frame)=={'type','requestId','text'},frame
                answer+=frame['text']
            elif frame['type'] in ('done','cancelled','error'):
                expected={'type','requestId','code','message'} if frame['type']=='error' else {'type','requestId'}
                assert set(frame)==expected,frame
                return answer,frame,frames
            else:raise AssertionError(frame)
    def usage(self,identifier):
        deadline=time.monotonic()+2
        while time.monotonic()<deadline:
            for line in list(self.errors):
                # Upstream logging can leave a line without LF. The worker's
                # standalone diagnostic frame may therefore follow that prefix.
                position=line.find('MINI_AI_USAGE ')
                if position>=0:
                    item=json.loads(line[position+len('MINI_AI_USAGE '):])
                    if item['requestId']==identifier:return item
            time.sleep(.01)
        raise AssertionError('Missing diagnostic usage for '+identifier)
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
        answer,terminal,frames=session.generated('cap-one',[{'role':'user','content':'Перечисли все целые числа от 1 до 10000 по порядку.'}],1)
        assert terminal['type']=='error' and terminal['code']=='OUTPUT_LIMIT',terminal
        usage=session.usage('cap-one')
        assert usage['finish_reason']=='length' and usage['output_tokens']==1 and usage['evidence_selection_grammar'] is False,usage
        reports.append({'case':'one-token-cap-never-done','success':True,'terminal':terminal,'usage':usage,'visible_prefix':answer})
        answer,terminal,_=session.generated('after-cap',[{'role':'system','content':'Ответь одним числом.'},{'role':'user','content':'Сколько будет 2 + 2?'}],32)
        assert terminal['type']=='done' and answer.strip()=='4',(terminal,answer)
        assert session.usage('after-cap')['finish_reason']=='eos'
        reports.append({'case':'cap-then-clean-eog-recovery','success':True,'answer':answer,'terminal':terminal})
        marker='MINI_AI_EVIDENCE_SELECTION_JSON_V1\n'
        messages=[{'role':'system','content':marker+'Return only the specified evidence-selection JSON. Unit E1 states: 4. mode=answer, answer=4, brief=[E1], details=[], missing=none.'},
                  {'role':'user','content':'What is 2+2 according to E1?'}]
        answer,terminal,_=session.generated('selector-grammar',messages,256)
        assert terminal['type']=='done',terminal
        selected=json.loads(answer,object_pairs_hook=strict_object)
        assert list(selected)==['mode','answer','brief','details','missing'],selected
        assert selected['mode'] in ('answer','clarify','insufficient')
        assert isinstance(selected['answer'],str) and len(selected['answer'])<=400
        selected['answer'].encode('utf-8',errors='strict') # No unpaired escaped Unicode surrogates.
        assert selected['missing'] in ('none','scope','evidence','detail','individual')
        for key in ('brief','details'):
            assert isinstance(selected[key],list) and len(selected[key])<=32
            assert all(isinstance(value,str) and re.fullmatch(r'E[1-9][0-9]{0,2}',value) for value in selected[key])
        usage=session.usage('selector-grammar')
        assert usage['evidence_selection_grammar'] is True and usage['finish_reason']=='eos',usage
        reports.append({'case':'actual-constrained-selector-json','success':True,'actual_json':selected,'usage':usage,
                        'scope':'Syntactic grammar activation and bounded output only; no normative correctness claim'})
        translation_marker='MINI_AI_COMPLETE_EVIDENCE_TRANSLATION_JSON_V1\n'
        pieces={'target_language':'kz','pieces':[{'id':'P1','text':'Работник подаёт заявление в течение 2 рабочих дней.'}]}
        messages=[{'role':'system','content':translation_marker+'Переведите каждый полный блок с русского на казахский. Верните только JSON {"translations":[{"id":"P1","text":"полный перевод"}]}. Сохраните точные ID, порядок и все числа. Не дублируйте поля и не добавляйте объяснений.'},
                  {'role':'user','content':json.dumps(pieces,ensure_ascii=False)}]
        answer,terminal,_=session.generated('translation-grammar',messages,256)
        assert terminal['type']=='done',terminal
        translated=json.loads(answer,object_pairs_hook=strict_object)
        assert list(translated)==['translations'] and len(translated['translations'])==1,translated
        piece=translated['translations'][0]
        assert list(piece)==['id','text'] and piece['id']=='P1' and isinstance(piece['text'],str) and piece['text'].strip(),translated
        piece['text'].encode('utf-8',errors='strict')
        assert re.findall(r'\d+',piece['text'])==['2'] and any(c in piece['text'] for c in 'әғқңөұүһіӘҒҚҢӨҰҮҺІ'),translated
        usage=session.usage('translation-grammar')
        assert usage['evidence_selection_grammar'] is False and usage['constrained_grammar']=='complete-evidence-translation-json-v1' and usage['finish_reason']=='eos',usage
        reports.append({'case':'actual-constrained-complete-evidence-translation-json','success':True,'actual_json':translated,'usage':usage,
                        'scope':'Tiny local translation transport: strict JSON keys/order/ID/number and Kazakh text. Does not certify normative semantic equivalence.'})
        answer,terminal,_=session.generated('translation-marker-in-user',[{'role':'system','content':'Ответь одним числом.'},{'role':'user','content':translation_marker+'Сколько будет 2 + 2?'}],32)
        assert terminal['type']=='done' and answer.strip()=='4',(terminal,answer)
        usage=session.usage('translation-marker-in-user')
        assert usage['evidence_selection_grammar'] is False and usage['constrained_grammar']=='none',usage
        reports.append({'case':'user-translation-marker-does-not-activate-grammar','success':True,'answer':answer,'usage':usage})
        answer,terminal,_=session.generated('marker-in-user',[{'role':'system','content':'Ответь одним числом.'},{'role':'user','content':marker+'Сколько будет 2 + 2?'}],32)
        assert terminal['type']=='done' and answer.strip()=='4',(terminal,answer)
        usage=session.usage('marker-in-user')
        assert usage['evidence_selection_grammar'] is False,usage
        reports.append({'case':'user-marker-does-not-activate-grammar','success':True,'answer':answer,'usage':usage})
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
    completed=True
finally:
    (art/"native-controls.json").write_text(json.dumps({"state":"passed" if completed else "incomplete-or-failed","scope":"real native transport and rejection on the unchanged full evidence-capable package; no assistant/corpus quality claim","model":str(source),"cases":reports},ensure_ascii=False,indent=2),encoding="utf-8")
    if json.loads(lock.read_text()).get("pid")==owner["pid"]:lock.unlink()
print(json.dumps(reports,ensure_ascii=False))
