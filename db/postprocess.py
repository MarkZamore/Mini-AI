"""Local, resumable evidence checks before SQLite publication."""
import argparse, concurrent.futures, json, sys, time
from pathlib import Path
from common import DB_ROOT, read_json

def process_document(path,root):
    # Windows releases this byte lock even after abrupt process termination.
    # Separate resumed batches cannot run the expensive evidence retry twice.
    import msvcrt
    locks=Path(root)/'.work'/'postprocess_locks';locks.mkdir(parents=True,exist_ok=True)
    with open(locks/(Path(path).stem+'.lock'),'a+b') as lock:
        if lock.tell()==0:lock.write(b'\0');lock.flush()
        while True:
            try:lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1);break
            except OSError:time.sleep(.2)
        try:return _process_document(path,root)
        finally:lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_UNLCK,1)

def _process_document(path,root):
    import cv2
    cv2.setNumThreads(1)
    from repair_ocr import repair_document
    from enrich import enrich_excel
    from quality_finalize import finalize_document
    d=read_json(path)
    if any('ocr_evidence_artifact_id' in b['payload'] for b in d['blocks']):d=repair_document(d,path,root)
    if d['source']['format'] in {'xls','xlsx'}:d=enrich_excel(d,path,root)
    d=finalize_document(d,path,root)
    return {'id':d['document_id'],'status':d['extraction']['status']}

def postprocess_all(root,workers=4,published_only=False):
    paths=sorted((Path(root)/'documents').glob('*.json'))
    if published_only:
        finished={r['id'] for r in read_json(Path(root)/'catalog.json')['results'] if r['status']!='failed'}
        paths=[p for p in paths if p.stem in finished]
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        futures={pool.submit(process_document,str(path),str(root)):path.name for path in paths}
        for future in concurrent.futures.as_completed(futures):
            result=future.result()
            if result['status']=='failed':print(json.dumps({'postprocessing':result}),flush=True)
    print(json.dumps({'postprocessed_documents':len(paths),'workers':workers}),flush=True)

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser();parser.add_argument('--db',default=str(DB_ROOT));parser.add_argument('--workers',type=int,default=4)
    parser.add_argument('--published-only',action='store_true');args=parser.parse_args()
    postprocess_all(Path(args.db),args.workers,args.published_only)
