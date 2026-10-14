"""Explicit batch conversion and resumable publication of normalized JSON."""
import argparse, concurrent.futures, hashlib, json, os, re, shutil, sys, tempfile, time
from pathlib import Path
from common import DB_ROOT, atomic_json, native, read_json, relative, sha256, utc, validate_document
from formats import Context, extract_docx, extract_xlsx, extract_xls, office_convert
from pdf_ocr import extract_pdf, ocr_embedded_images

SETTINGS={'ocr_languages':['rus','kaz','eng'],'ocr_dpi':300,'ocr_retry_dpi':400,'ocr_engine':'tesseract_lstm_best',
          'workers':2,'fragment_characters':2000,'low_word_confidence':80,'max_render_dimension':4200,'max_retry_dimension':5600,'external_services':False}

def fingerprint(root,tools):
    for name,metadata in tools['downloads'].items():
        if name.endswith('.traineddata') and sha256(Path(tools['tessdata'])/name)!=metadata['sha256']:
            raise ValueError('OCR model hash mismatch: '+name)
    code={p.name:sha256(p) for p in Path(root).glob('*.py') if p.name in {'common.py','formats.py','pdf_ocr.py','convert.py','office_bridge.py'}}
    value={'code':code,'schema':sha256(Path(root)/'schema'/'document.schema.json'),'settings':SETTINGS,'models':{k:v['sha256'] for k,v in tools['downloads'].items() if k.endswith('.traineddata')},'tools':tools['versions'],'packages':tools['packages']}
    return hashlib.sha256(json.dumps(value,sort_keys=True).encode()).hexdigest()

def document_identifier(item):
    match=re.search(r'/download/document/(\d+)/',item['url'])
    return match.group(1) if match else hashlib.sha256(item['url'].encode()).hexdigest()[:32]

def base_document(item,root,source_root,tools,fp):
    sid=document_identifier(item); source=Path(source_root).joinpath(*item['path'].split('/'))
    return {'schema_version':'1.0','document_id':'satbayev:document:'+sid,'source_version_sha256':item['sha256'],
            'title':Path(item['original_name']).stem,
            'source':{'path':relative(source,root),'name':item['original_name'],'format':Path(item['path']).suffix.lstrip('.').lower(),
                      'size':item['size'],'sha256':item['sha256'],'urls':item['source_urls'],'sections':sorted(set(p['section'] for p in item['placements'])),
                      'manifest_path':item['path']},
            'artifacts':[],'blocks':[],'assets':[],'references':[],
            'extraction':{'status':'failed','fingerprint':fp,'converted_at':utc(),'tools':{'versions':tools['versions'],'packages':tools['packages'],'model_hashes':{k:v['sha256'] for k,v in tools['downloads'].items() if k.endswith('.traineddata')}},
                          'settings':SETTINGS,'coverage':{},'warnings':[],'errors':[]}}

def collect_references(document):
    for b in document['blocks']:
        texts=[(b['text'],b['source_refs'])]
        if b['kind']=='table': texts.extend((c['text'],c['source_refs']) for c in b['payload']['cells'])
        for text,refs in texts:
            for match in re.finditer(r'https?://[^\s<>"\)]+',text):
                document['references'].append({'kind':'url','value':match.group(0).rstrip('.,;'),'block_id':b['id'],'evidence':match.group(0),'source_refs':refs})
            for match in re.finditer(r'(?i)\b(?:ДП|Ф|СТ|П)\s+(?:КазНИТУ|KaзНИТУ|НИЛАС|ЛЦТМ)\s*\d+(?:[.\-–]\d+)*',text):
                document['references'].append({'kind':'document_code','value':match.group(0),'block_id':b['id'],'evidence':match.group(0),'source_refs':refs})
        for url in b['payload'].get('hyperlinks',[]):
            document['references'].append({'kind':'url','value':url,'block_id':b['id'],'evidence':b['text'],'source_refs':b['source_refs']})

def convert_one(item,root,source_root,tools,fp,force=False):
    root=Path(root); sid=document_identifier(item); output=root/'documents'/(sid+'.json')
    document=base_document(item,root,source_root,tools,fp); original=Path(source_root).joinpath(*item['path'].split('/'))
    try:
        if sha256(original)!=item['sha256']: raise ValueError('Original differs from docs/updates.json SHA-256')
        if output.exists() and not force:
            existing=read_json(output)
            if existing['source_version_sha256']==item['sha256'] and existing['extraction']['fingerprint']==fp and existing['extraction']['status']!='failed':
                validate_document(existing,root)
                if all((root/a['path']).is_file() and sha256(root/a['path'])==a['sha256'] for a in existing['artifacts']): return {'id':sid,'reused':True,'status':existing['extraction']['status'],'blocks':len(existing['blocks'])}
        ctx=Context(document,root)
        workbase=root/'.work'; workbase.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=sid+'_',dir=str(workbase)) as work:
            ext=document['source']['format']
            if ext=='pdf': extract_pdf(ctx,original,tools,work)
            elif ext=='docx': extract_docx(ctx,original); ocr_embedded_images(ctx,tools,work)
            elif ext in {'doc','rtf'}:
                a,p=office_convert(ctx,original,work,tools,'docx'); extract_docx(ctx,p,a['id']); ocr_embedded_images(ctx,tools,work)
                ctx.warn('derived_locator','XML locators refer to preserved LibreOffice DOCX, not original binary/RTF page positions.')
            elif ext=='xlsx': extract_xlsx(ctx,original); ocr_embedded_images(ctx,tools,work)
            elif ext=='xls':
                extract_xls(ctx,original)
                a,p=office_convert(ctx,original,work,tools,'xlsx')
                start=len(document['blocks']); extract_xlsx(ctx,p,a['id'])
                derived=document['blocks'][start:]; native_tables={b['payload']['sheet']:b for b in document['blocks'][:start] if b['kind']=='table'}
                id_map={}
                for b in derived:
                    if b['kind']=='table':
                        native_table=native_tables.get(b['payload']['sheet']); id_map[b['id']]=native_table['id'] if native_table else None
                        if native_table:
                            cells={(c['row'],c['column']):c for c in native_table['payload']['cells']}
                            for c in b['payload']['cells']:
                                original_cell=cells.get((c['row'],c['column']))
                                if original_cell and c['formula']:
                                    original_cell['formula']=c['formula']; original_cell['cached_value']=original_cell['value']
                                    original_cell['source_refs'].extend(c['source_refs'])
                    elif b['parent_id'] in id_map: b['parent_id']=id_map[b['parent_id']]
                document['blocks']=[b for b in document['blocks'] if b not in derived or b['kind']!='table']
                for i,b in enumerate(document['blocks']): b['order']=i
                ocr_embedded_images(ctx,tools,work)
            else: raise ValueError('Unsupported source format '+ext)
        collect_references(document)
        incomplete={'ocr_failed','ocr_needs_review','scan_table_needs_review','pdf_encoding_damaged','image_ocr_unavailable','unparsed_attachment','embedding_depth','pdf_table_detection','merged_continuation_text','shared_formula','formula_cache_missing','xls_native_limitations'}
        document['extraction']['status']='partial' if any(w['code'] in incomplete for w in document['extraction']['warnings']) else 'complete'
        if not document['blocks']: document['extraction']['status']='partial'; ctx.warn('no_content','No structural content extracted; verify original.')
        document['extraction']['converted_at']=utc(); validate_document(document,root); atomic_json(output,document)
        return {'id':sid,'reused':False,'status':document['extraction']['status'],'blocks':len(document['blocks']),'ocr_pages':sum(p.get('ocr',False) for p in document['extraction']['coverage'].get('pages',[]))}
    except Exception as exc:
        document['extraction']['errors'].append(type(exc).__name__+': '+str(exc)); document['extraction']['status']='failed'
        # Never overwrite an already usable result with a failed replacement.
        if output.exists() and read_json(output)['extraction']['status']!='failed':
            return {'id':sid,'status':'failed','retained_previous':True,'error':str(exc)}
        validate_document(document,root); atomic_json(output,document)
        return {'id':sid,'status':'failed','error':str(exc)}

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--db',default=str(DB_ROOT)); parser.add_argument('--ids',nargs='*'); parser.add_argument('--force',action='store_true'); parser.add_argument('--workers',type=int,default=2); parser.add_argument('--skip-index',action='store_true')
    args=parser.parse_args(); root=Path(args.db).resolve(); source_root=root.parent/'docs'
    tools=read_json(root/'_runtime'/'tools.json'); fp=fingerprint(root,tools); manifest=read_json(source_root/'updates.json')
    manifest_before=sha256(source_root/'updates.json'); items=manifest['files']
    if args.ids: items=[i for i in items if document_identifier(i) in args.ids]
    # Native/text documents first; OCR-heavy PDF work follows, with deterministic publication per document.
    items=sorted(items,key=lambda i:(i['path'].lower().endswith('.pdf'),i['path'].lower().endswith('.pdf') and i['size']))
    baseline={'started_at':utc(),'status':'partial','found':len(items),'fingerprint':fp,'results':[],'manifest_sha256':manifest_before}
    atomic_json(root/'catalog.json',baseline)
    with concurrent.futures.ProcessPoolExecutor(max_workers=min(2,max(1,args.workers))) as pool:
        futures={pool.submit(convert_one,item,str(root),str(source_root),tools,fp,args.force):document_identifier(item) for item in items}
        for future in concurrent.futures.as_completed(futures):
            try: result=future.result()
            except Exception as exc: result={'id':futures[future],'status':'failed','error':str(exc)}
            baseline['results'].append(result); atomic_json(root/'catalog.json',baseline)
            print(json.dumps({'processed':len(baseline['results']),'total':len(items),**result},ensure_ascii=False),flush=True)
    baseline['finished_at']=utc(); baseline['manifest_unchanged']=manifest_before==sha256(source_root/'updates.json')
    baseline['status']='complete' if all(r['status']!='failed' for r in baseline['results']) and baseline['manifest_unchanged'] else 'partial'
    atomic_json(root/'catalog.json',baseline)
    if not args.skip_index:
        from search import build_index
        print(json.dumps(build_index(root),ensure_ascii=False),flush=True)
    print(json.dumps({'batch_status':baseline['status'],'processed':len(baseline['results']),'complete':sum(r['status']=='complete' for r in baseline['results']),'partial':sum(r['status']=='partial' for r in baseline['results']),'failed':sum(r['status']=='failed' for r in baseline['results'])}),flush=True)

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8'); main()
