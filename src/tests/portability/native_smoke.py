"""Exercise the actual EXE read-only. Store observer reports under src/build only."""
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    root=Path(__file__).resolve().parents[3]
    exe=root/'EnterpriseAI.exe'
    output=root/'src/build/test-results'
    output.mkdir(parents=True,exist_ok=True)
    before=hashlib.sha256((root/'db/index.sqlite').read_bytes()).hexdigest()
    timings={}
    def run(*args):
        started=time.perf_counter()
        result=subprocess.run([str(exe),*args],cwd=str(root.parent),capture_output=True,encoding='utf-8',timeout=60)
        timings[' '.join(args[:1])]=round((time.perf_counter()-started)*1000,2)
        if result.returncode!=0 or result.stderr:
            raise AssertionError((args,result.returncode,result.stderr,result.stdout[:1000]))
        assert '\ufffd' not in result.stdout,'Unicode replacement in native stdout'
        return json.loads(result.stdout)
    status=run('--status')
    assert Path(status['root']).resolve()==root.resolve()
    assert set(status['modules'])=={'database','documents','search','graph','inference','assistant','sources'}
    assert status['panels']==['assistant','graph']
    hits=run('--search','академическая политика','10')
    assert len(hits)==10 and all(h['source_refs'] and len(h['source_sha256'])==64 for h in hits)
    fragment=run('--get-fragment',hits[0]['fragment_id'])
    for field in ('fragment_id','document_id','text','source_refs','quality','span','warnings','source_sha256'):
        assert fragment[field]==hits[0][field],field
    resolved=run('--resolve',json.dumps(fragment['source_refs'][0],ensure_ascii=False))
    assert resolved['verified'] and Path(resolved['original_path']).resolve().is_relative_to((root/'docs').resolve())
    graph=run('--graph')
    assert len(graph['nodes'])==599 and len(graph['edges'])==647 and graph['unresolved_count']==1579
    assert sum(n['degree']==0 for n in graph['nodes'])==335
    pairs={tuple(sorted((e['source_id'],e['target_id']))) for e in graph['edges']}
    assert len(pairs)==290
    for node in graph['nodes']:
        assert abs(node['radius']-min(56,16+6*node['degree']**0.5))<1e-10
    model=run('--model-status')
    assert model['success'] is False and model['error']['code']=='MODEL_WORKER_MISSING'
    after=hashlib.sha256((root/'db/index.sqlite').read_bytes()).hexdigest()
    assert before==after
    assert not any(Path(str(root/'db/index.sqlite')+s).exists() for s in ('-journal','-wal','-shm'))
    report={'success':True,'actualExe':str(exe),'nonDeveloperWorkingDirectory':str(root.parent),
            'unicodeExact':True,'searchResults':len(hits),'fragmentAndSourceVerified':True,
            'graphNodes':599,'confirmedEvidenceEdges':647,'visualPairs':290,'isolates':335,
            'unresolvedReferences':1579,'missingModelError':model['error'],
            'indexUnchangedSha256':after,'milliseconds':timings,
            'limitations':['No real model generation','No clean Windows VM','No ProcMon filesystem/registry trace']}
    (output/'native-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':
    main()
