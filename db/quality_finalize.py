"""Explicit diagnostics for empty OCR and PDF geometry outside the visible page."""
import collections, json, statistics, tempfile, shutil
from pathlib import Path
from common import atomic_json, sha256, validate_document, read_json, stored_path, native
from formats import Context, quality, warning

QUALITY_HASH=sha256(Path(__file__))

def retry_empty(ctx,row,size,evidence,root):
    from PIL import Image
    from pdf_ocr import render_page, recognize
    from repair_ocr import parse_tsv
    tools=read_json(Path(root)/'_runtime'/'tools.json')
    with tempfile.TemporaryDirectory(dir=Path(root)/'.work') as work:
        source=Path(work)/'source.pdf'
        shutil.copyfile(native(stored_path(root,ctx.doc['source']['path'],allow_source=True)),source)
        image=render_page(source,row['page'],tools,work,400,size)
        _,raw=recognize(image,tools,Path(work)/'empty_retry',400,11)
        words,pixels=parse_tsv(raw)
        tsv,_=ctx.save_bytes(raw,'tsv','original','ocr_tsv_empty_retry')
        ref=ctx.loc(kind='pdf_region',page=row['page'],bbox=[0,0,*size],page_size=size)
        render,_=ctx.save_bytes(image.read_bytes(),'png','original','page_render',[ref])
        sx,sy=size[0]/pixels[0],size[1]/pixels[1]
        confidence=statistics.mean(w['confidence'] for w in words) if words else None
        ev,_=ctx.save_bytes(json.dumps({'page':row['page'],'page_size':size,'selected_variant':0,
            'variants':[{'words':words,'confidence':confidence,'dpi':400,'psm':11,'artifact_id':tsv['id']}],
            'render_artifact_id':render['id'],'pixel_to_page':[sx,0,0,sy,0,0],
            'orientation':'rendered_pdf_page','retry_of':evidence,'parser':'quote_safe_tsv_1.0'},ensure_ascii=False).encode(),
            'json',evidence,'ocr_evidence_empty_retry')
    groups=collections.defaultdict(list)
    for word in words:
        x0,y0,x1,y1=word['bbox'];groups[tuple(word['line'])].append(dict(word,page_bbox=[x0*sx,y0*sy,x1*sx,y1*sy]))
    for group in groups.values():
        box=[min(w['page_bbox'][0] for w in group),min(w['page_bbox'][1] for w in group),max(w['page_bbox'][2] for w in group),max(w['page_bbox'][3] for w in group)]
        spans=[];offset=0
        for word in group:
            spans.append({'text':word['text'],'char_start':offset,'char_end':offset+len(word['text']),'bbox':word['page_bbox'],'confidence':word['confidence']});offset+=len(word['text'])+1
        flag=warning('ocr_empty_retry','Recovered by 400 DPI sparse-text segmentation after empty OCR; requires local review.')
        ctx.block('paragraph',' '.join(w['text'] for w in group),[ctx.loc(kind='pdf_region',page=row['page'],bbox=box,page_size=size)],
            payload={'spans':spans,'ocr_evidence_artifact_id':ev['id'],'reading_order':'ocr_line'},
            q=quality('tesseract_lstm',statistics.mean(w['confidence'] for w in group),[flag],True))
    row.update(words=len(words),selected_dpi=400,confidence=confidence,evidence=ev['id'],empty_retry=True)
    ctx.warn('ocr_empty_retry','Page '+str(row['page'])+' received a 400 DPI sparse-text retry; original empty result is retained.')
    return len(words)

def finalize_document(d,path,root):
    if d['source']['format']!='pdf' or d['extraction']['tools'].get('geometry_diagnostics')==QUALITY_HASH:return d
    ctx=Context(d,root);ctx.serial=max((b['order'] for b in d['blocks']),default=-1)+1
    outside=[]
    for b in d['blocks']:
        refs=list(b['source_refs'])
        if b['kind']=='table':refs.extend(r for c in b['payload']['cells'] for r in c['source_refs'])
        for ref in refs:
            if ref['kind']!='pdf_region' or 'page_size' not in ref:continue
            x0,y0,x1,y1=ref['bbox'];w,h=ref['page_size']
            if x0<-.5 or y0<-.5 or x1>w+.5 or y1>h+.5:
                flag=warning('pdf_region_outside_page','Source text geometry extends beyond the visible page; coordinates are retained unchanged.')
                if flag not in b['quality']['warnings']:b['quality']['warnings'].append(flag)
                b['quality']['needs_review']=True;outside.append(ref['page'])
                break
    if outside:
        ctx.warn('pdf_region_outside_page','Blocks outside visible page bounds: '+str(len(outside))+'; pages: '+str(sorted(set(outside))))
    empty=[]
    for row in d['extraction']['coverage'].get('pages',[]):
        result=row.get('ocr_result',row.get('result',{}))
        # Extraction stores engine result directly in the coverage row.
        words=result.get('words',row.get('words'))
        if row.get('ocr') and words==0 and row.get('native_characters',0)<40:
            page=row['page'];empty.append(page)
            evidence=row.get('evidence',result.get('evidence'))
            art=next((a for a in d['artifacts'] if a['id']==evidence),None)
            size=None
            if art:
                size=read_json(Path(root)/art['path']).get('page_size')
            if not size:
                size=next((r['page_size'] for b in d['blocks'] for r in b['source_refs'] if r['kind']=='pdf_region' and r.get('page')==page and 'page_size' in r),None)
            if size and art and not row.get('empty_retry'):
                try:
                    if retry_empty(ctx,row,size,evidence,root):
                        empty.pop();continue
                except Exception as exc:ctx.warn('ocr_empty_retry_failed','Page '+str(page)+': '+str(exc))
            flag=warning('ocr_no_text','OCR returned no words; a blank page cannot be distinguished reliably from an unreadable scan without local review.')
            if size and not any(b['payload'].get('diagnostic')=='ocr_no_text' and any(r.get('page')==page for r in b['source_refs']) for b in d['blocks']):
                ctx.block('unparsed','',[ctx.loc(kind='pdf_region',page=page,bbox=[0,0,*size],page_size=size)],
                    payload={'diagnostic':'ocr_no_text','ocr_evidence_artifact_id':evidence},q=quality('unprocessed',warnings=[flag],review=True))
    if empty:ctx.warn('ocr_no_text','No OCR words on pages '+str(empty)+'; preserved rendering and engine output require local review.')
    if (outside or empty or any(r.get('empty_retry') for r in d['extraction']['coverage'].get('pages',[]))) and d['extraction']['status']!='failed':d['extraction']['status']='partial'
    d['blocks'].sort(key=lambda b:(b['source_refs'][0].get('page',0),b['source_refs'][0].get('bbox',[0,0])[1],b['source_refs'][0].get('bbox',[0,0])[0],b['order']))
    for i,b in enumerate(d['blocks']):b['order']=i
    d['references']=[]
    from convert import collect_references
    collect_references(d)
    d['extraction']['tools']['geometry_diagnostics']=QUALITY_HASH
    validate_document(d,root);atomic_json(path,d)
    return d
