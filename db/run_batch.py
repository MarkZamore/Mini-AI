"""Resume a verified extraction recipe with independently chosen CPU concurrency."""
import argparse, concurrent.futures, json, sys
from pathlib import Path
from common import DB_ROOT, atomic_json, read_json, sha256, utc, validate_document
from convert import convert_one, document_identifier, fingerprint

def initialize_worker():
    import cv2
    cv2.setNumThreads(1)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--db',default=str(DB_ROOT))
    parser.add_argument('--workers',type=int,default=10)
    parser.add_argument('--skip-index',action='store_true')
    args=parser.parse_args(); root=Path(args.db).resolve(); source_root=root.parent/'docs'
    tools=read_json(root/'_runtime'/'tools.json'); fp=fingerprint(root,tools)
    manifest_hash=sha256(source_root/'updates.json'); items=read_json(source_root/'updates.json')['files']
    old=read_json(root/'catalog.json') if (root/'catalog.json').exists() else {}
    if old and (old.get('fingerprint')!=fp or old.get('manifest_sha256')!=manifest_hash):
        raise ValueError('Recipe or source manifest changed; use convert.py for a new extraction recipe.')
    baseline={**old,'started_at':old.get('started_at',utc()),'status':'partial','found':len(items),
              'fingerprint':fp,'manifest_sha256':manifest_hash,'results':[],
              'scheduling':{'workers':args.workers,'opencv_threads_per_worker':1,'resumed_at':utc()}}
    pending=[]
    for item in items:
        sid=document_identifier(item); output=root/'documents'/(sid+'.json')
        if output.exists():
            d=read_json(output)
            if d['source_version_sha256']==item['sha256'] and d['extraction']['fingerprint']==fp and d['extraction']['status']!='failed':
                validate_document(d,root)
                baseline['results'].append({'id':sid,'reused':True,'status':d['extraction']['status'],'blocks':len(d['blocks']),
                  'ocr_pages':sum(p.get('ocr',False) for p in d['extraction']['coverage'].get('pages',[]))})
                continue
        pending.append(item)
    # Longest remaining PDFs first reduces the tail while short tasks fill free workers.
    pending.sort(key=lambda i:i['size'],reverse=True)
    atomic_json(root/'catalog.json',baseline)
    print(json.dumps({'reused':len(baseline['results']),'remaining':len(pending),'workers':args.workers}),flush=True)
    with concurrent.futures.ProcessPoolExecutor(max_workers=max(1,args.workers),initializer=initialize_worker) as pool:
        futures={pool.submit(convert_one,item,str(root),str(source_root),tools,fp):document_identifier(item) for item in pending}
        for future in concurrent.futures.as_completed(futures):
            try: result=future.result()
            except Exception as exc: result={'id':futures[future],'status':'failed','error':str(exc)}
            baseline['results'].append(result); atomic_json(root/'catalog.json',baseline)
            print(json.dumps({'processed':len(baseline['results']),'total':len(items),**result}),flush=True)
    baseline['finished_at']=utc(); baseline['manifest_unchanged']=manifest_hash==sha256(source_root/'updates.json')
    baseline['status']='complete' if baseline['manifest_unchanged'] and all(r['status']!='failed' for r in baseline['results']) else 'partial'
    atomic_json(root/'catalog.json',baseline)
    if not args.skip_index:
        from search import build_index
        print(json.dumps(build_index(root)),flush=True)
        for result in baseline['results']:
            d=read_json(root/'documents'/(result['id']+'.json'))
            result.update(status=d['extraction']['status'],blocks=len(d['blocks']))
        baseline['status']='complete' if baseline['manifest_unchanged'] and all(r['status']=='complete' for r in baseline['results']) else 'partial'
        baseline['finished_at']=utc();atomic_json(root/'catalog.json',baseline)

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8'); main()
