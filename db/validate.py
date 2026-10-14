"""Integrity, provenance and search checks; emits a current report, not a change log."""
import argparse, collections, concurrent.futures, hashlib, json, os, sqlite3, statistics, sys, time, zipfile
from pathlib import Path
from common import DB_ROOT, atomic_json, read_json, sha256, stored_path, utc, validate_document, native
from search import Database

QUERIES=['академическая политика','качество','антикоррупционный','управление рисками','договор','практика','магистрант','приказ','учебная нагрузка','исследование','финансы','студент','документ','кафедра','отчет','401','602','706','711','КазНИТУ']

def verify(root=DB_ROOT):
    root=Path(root); manifest_path=root.parent/'docs'/'updates.json'; manifest=read_json(manifest_path)
    source_map={f['sha256']:f for f in manifest['files']}; report={'verified_at':utc(),'source_documents':len(manifest['files']),'json_documents':0,'statuses':{},'formats':{},'blocks':0,'ocr_pages':0,'warnings':{},'errors':[],
        'originals_verified':0,'artifacts_verified':0,'locator_types':{},'human_ocr_review_performed':False}
    statuses=collections.Counter(); formats=collections.Counter(); format_statuses=collections.defaultdict(collections.Counter); warnings=collections.Counter(); locators=collections.Counter(); artifact_cache={}; doc_ids=set(); current_results={}
    hash_paths={root.parent/'docs'/item['path'] for item in manifest['files']}
    for path in (root/'documents').glob('*.json'):
        try:hash_paths.update(root/a['path'] for a in read_json(path)['artifacts'])
        except Exception:pass  # The per-document validation below records exact diagnostics.
    hashes={};hash_errors={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs={pool.submit(sha256,path):str(path) for path in hash_paths}
        for job in concurrent.futures.as_completed(jobs):
            try:hashes[jobs[job]]=job.result()
            except Exception as exc:hash_errors[jobs[job]]=str(exc)
    print(json.dumps({'integrity_hashes_checked':len(hashes),'hash_workers':4}),flush=True)
    def checked_hash(path):
        key=str(path)
        if key in hash_errors:raise ValueError(hash_errors[key])
        return hashes[key] if key in hashes else sha256(path)
    for path in sorted((root/'documents').glob('*.json')):
        try:
            d=read_json(path);validate_document(d,root);doc_ids.add(d['document_id']);report['json_documents']+=1;xml_cache={};xpath_cache=set()
            original=stored_path(root,d['source']['path'],allow_source=True)
            if checked_hash(original)!=d['source_version_sha256']:raise ValueError('Original SHA-256 mismatch')
            if d['source_version_sha256'] not in source_map:raise ValueError('JSON source missing from source manifest')
            report['originals_verified']+=1; statuses[d['extraction']['status']]+=1;formats[d['source']['format']]+=1
            format_statuses[d['source']['format']][d['extraction']['status']]+=1
            current_results[path.stem]={'status':d['extraction']['status'],'blocks':len(d['blocks'])}
            report['blocks']+=len(d['blocks']);report['ocr_pages']+=sum(p.get('ocr',False) for p in d['extraction']['coverage'].get('pages',[]))
            warnings.update(w['code'] for w in d['extraction']['warnings'])
            aids={'original'}|{a['id'] for a in d['artifacts']}
            for a in d['artifacts']:
                if a['derived_from'] not in aids:raise ValueError('Artifact derivation chain is broken')
                ap=stored_path(root,a['path'])
                if a['path'] not in artifact_cache:artifact_cache[a['path']]=checked_hash(ap)
                if artifact_cache[a['path']]!=a['sha256']:raise ValueError('Artifact SHA-256 mismatch')
            pdf_sizes=None
            if d['source']['format']=='pdf':
                from pypdf import PdfReader
                reader=PdfReader(native(original));pdf_sizes=[]
                for page in reader.pages:
                    size=[float(page.mediabox.width),float(page.mediabox.height)]
                    if page.get('/Rotate',0)%180:size.reverse()
                    pdf_sizes.append(size)
            for b in d['blocks']+d['assets']+d['references']:
                refs=list(b['source_refs'])
                if b.get('kind')=='table':refs.extend(r for c in b['payload']['cells'] for r in c['source_refs'])
                for ref in refs:locators[ref['kind']]+=1
                for ref in refs:
                    if ref['source_sha256']!=d['source_version_sha256'] or ref['artifact_id'] not in aids:raise ValueError('Invalid asset/reference source version or artifact')
                    if ref['kind']=='pdf_region' and ref['artifact_id']=='original':
                        if not pdf_sizes or not 1<=ref['page']<=len(pdf_sizes):raise ValueError('PDF source page does not exist')
                        if 'page_size' in ref and any(abs(a-b)>.5 for a,b in zip(ref['page_size'],pdf_sizes[ref['page']-1])):raise ValueError('PDF page dimensions mismatch')
                    if ref['kind'] in {'ooxml_node','drawing'}:
                        art=next((a for a in d['artifacts'] if a['id']==ref['artifact_id']),None)
                        package=original if ref['artifact_id']=='original' else root/art['path']
                        key=('zip',str(package))
                        if key not in artifact_cache:
                            with zipfile.ZipFile(native(package)) as z:artifact_cache[key]=set(z.namelist())
                        if ref['part'] not in artifact_cache[key]:raise ValueError('Missing OOXML source part')
                        xml_key=(str(package),ref['part'])
                        if xml_key not in xml_cache:
                            from lxml import etree as E
                            with zipfile.ZipFile(native(package)) as z:tree=E.fromstring(z.read(ref['part']),parser=E.XMLParser(resolve_entities=False,no_network=True,huge_tree=True))
                            bindings={}
                            for node in tree.iter():bindings.update({k:v for k,v in node.nsmap.items() if k})
                            xml_cache[xml_key]=(tree,bindings)
                        xpath_key=(xml_key,ref['xpath'])
                        if xpath_key not in xpath_cache:
                            tree,bindings=xml_cache[xml_key];nodes=tree.xpath(ref['xpath'],namespaces=bindings)
                            if len(nodes)!=1:raise ValueError('OOXML locator does not resolve to exactly one node: '+ref['part']+' '+ref['xpath'])
                            xpath_cache.add(xpath_key)
            if d['extraction']['errors']:report['errors'].append({'document_id':d['document_id'],'conversion_errors':d['extraction']['errors']})
        except Exception as exc:report['errors'].append({'json':str(path.relative_to(root)),'reason':str(exc)})
    report['statuses']=dict(statuses);report['formats']=dict(formats);report['warnings']=dict(warnings);report['locator_types']=dict(locators)
    report['format_statuses']={k:dict(v) for k,v in format_statuses.items()}
    report['artifacts_verified']=sum(isinstance(k,str) for k in artifact_cache)
    expected={__import__('convert').document_identifier(f) for f in manifest['files']}
    actual={p.stem for p in (root/'documents').glob('*.json')}
    report['missing_documents']=sorted(expected-actual);report['extra_documents']=sorted(actual-expected)
    catalog=read_json(root/'catalog.json');report['manifest_unchanged']=sha256(manifest_path)==catalog['manifest_sha256']
    report['scheduling']=catalog.get('scheduling',{})
    for result in catalog['results']:
        if result['status']=='failed':report['errors'].append({'last_attempt_failed':result})
    with Database(root) as database:
        con=database.connection;report['sqlite_integrity']=con.execute('PRAGMA integrity_check').fetchone()[0]
        report['index_statistics']=json.loads(con.execute("SELECT value FROM metadata WHERE key='statistics'").fetchone()[0])
        indexed={r[0] for r in con.execute('SELECT document_id FROM documents')}
        if indexed!=doc_ids:report['errors'].append({'index_documents_mismatch':True})
        orphan=con.execute('SELECT count(*) FROM chunks c LEFT JOIN blocks b ON c.block_id=b.block_id WHERE b.block_id IS NULL').fetchone()[0]
        if orphan:report['errors'].append({'orphan_chunks':orphan})
        times=[];results=[]
        for query in QUERIES:database.search(query,10)
        for query in QUERIES:
            started=time.perf_counter();hits=database.search(query,10);elapsed=(time.perf_counter()-started)*1000
            times.append(elapsed);results.append({'query':query,'results':len(hits),'milliseconds':round(elapsed,3)})
            if hits and database.get_fragment(hits[0]['fragment_id'])['text']!=hits[0]['text']:report['errors'].append({'fragment_roundtrip_failed':query})
        report['search_benchmark']={'queries':results,'p50_ms':round(statistics.median(times),3),'p95_ms':round(sorted(times)[int(.95*(len(times)-1))],3),'warm_cache':True}
    report['integrity_ok']=not report['errors'] and not report['missing_documents'] and not report['extra_documents'] and report['manifest_unchanged'] and report['sqlite_integrity']=='ok'
    report['all_sources_processed']=report['json_documents']==report['source_documents'] and statuses['failed']==0
    report['sizes_bytes']={'documents':sum(p.stat().st_size for p in (root/'documents').rglob('*') if p.is_file()),
        'artifacts':sum(p.stat().st_size for p in (root/'artifacts').rglob('*') if p.is_file()),
        'index':(root/'index.sqlite').stat().st_size,'runtime':sum(p.stat().st_size for p in (root/'_runtime').rglob('*') if p.is_file())}
    atomic_json(root/'report.json',report)
    for result in catalog['results']:
        if result['id'] in current_results:result.update(current_results[result['id']])
    catalog['status']='complete' if report['integrity_ok'] and report['all_sources_processed'] and statuses['partial']==0 else 'partial'
    catalog['validation']={'report':'report.json','verified_at':report['verified_at'],'integrity_ok':report['integrity_ok']}
    atomic_json(root/'catalog.json',catalog)
    return report

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('--db',default=str(DB_ROOT));a=p.parse_args();r=verify(Path(a.db))
    print(json.dumps({k:v for k,v in r.items() if k not in {'search_benchmark','warnings'}},ensure_ascii=False));sys.exit(0 if r['integrity_ok'] else 1)
