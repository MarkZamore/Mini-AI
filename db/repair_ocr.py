"""Lossless quote-safe reparse of saved Tesseract TSV, without rerunning OCR."""
import collections,json,statistics,tempfile
from pathlib import Path
from PIL import Image
import pdfplumber
from pypdf import PdfReader
from common import atomic_json,native,read_json,sha256,stored_path,validate_document
from formats import Context,quality,warning

PARSER_HASH=sha256(Path(__file__))

def parse_tsv(data):
    words=[];size=None
    for line in data.decode('utf-8-sig').splitlines()[1:]:
        f=line.split('\t',11)
        if len(f)<12:continue
        if f[0]=='1':size=[int(f[8]),int(f[9])]
        if f[0]!='5' or not f[11].strip():continue
        x,y,w,h=map(int,f[6:10]);conf=max(0,min(100,float(f[10])))
        words.append({'text':f[11],'confidence':conf,'bbox':[x,y,x+w,y+h],'line':[int(f[2]),int(f[3]),int(f[4])]})
    return words,size

def repair_document(d,path,root):
    if d['extraction']['tools'].get('quote_safe_tsv_parser')==PARSER_HASH:return d
    arts={a['id']:a for a in d['artifacts']};eids={b['payload']['ocr_evidence_artifact_id'] for b in d['blocks'] if 'ocr_evidence_artifact_id' in b['payload']}
    if not eids:return d
    ctx=Context(d,root);recovered=0;pdf=None;reader=None;original=stored_path(root,d['source']['path'],allow_source=True)
    try:
        for eid in sorted(eids):
            item=arts[eid]
            if item['format']=='tsv':
                words,_=parse_tsv((Path(root)/item['path']).read_bytes())
                for b in d['blocks']:
                    if b['payload'].get('ocr_evidence_artifact_id')!=eid:continue
                    if [w['text'] for w in words]==[w['text'] for w in b['payload'].get('spans',[])]:continue
                    b['text']=' '.join(w['text'] for w in words);b['payload']['spans']=words
                    b['quality']['confidence']=statistics.mean(w['confidence'] for w in words) if words else None;b['quality']['needs_review']=True
                    b['quality']['warnings'].append(warning('ocr_tsv_reparsed','Text recovered quote-safely from saved engine output.'));recovered+=1
                continue
            if item['format']!='json':continue
            e=read_json(Path(root)/item['path']);different=False;sizes=[]
            for v in e['variants']:
                words,size=parse_tsv((Path(root)/arts[v['artifact_id']]['path']).read_bytes());sizes.append(size)
                if words!=v['words']:different=True
                v['words']=words;v['confidence']=statistics.mean(w['confidence'] for w in words) if words else None
            if not different:continue
            if sha256(original)!=d['source_version_sha256']:raise ValueError('Original changed before OCR reparse')
            if pdf is None:pdf=pdfplumber.open(native(original));reader=PdfReader(native(original))
            number=e['page'];page=pdf.pages[number-1];native_words=page.extract_words(x_tolerance=2,y_tolerance=3,keep_blank_chars=False)
            text=' '.join(w['text'] for w in native_words)
            if text.count('\ufffd')>max(2,len(text)*.01):native_words=[]
            old_choice=e['selected_variant'];choice=0;variants=e['variants']
            if len(variants)>1 and (variants[1]['confidence'] or 0)>(variants[0]['confidence'] or 0)+2 and len(variants[1]['words'])>=len(variants[0]['words'])*.8:choice=1
            chosen=variants[choice]
            with Image.open(Path(root)/arts[e['render_artifact_id']]['path']) as im:old_size=list(im.size)
            m=e['pixel_to_page'];region=[m[4],m[5],m[4]+m[0]*old_size[0],m[5]+m[3]*old_size[1]];size=sizes[choice]
            if choice!=old_choice:
                from pdf_ocr import direct_scan_image,render_page
                tools=read_json(Path(root)/'_runtime'/'tools.json');base=Path(root)/'.work';base.mkdir(exist_ok=True)
                with tempfile.TemporaryDirectory(dir=base) as work:
                    direct=direct_scan_image(page,reader.pages[number-1],work,number)
                    if direct:
                        with Image.open(direct['path']) as im:
                            out=Path(work)/'selected.png';im.resize(tuple(size),Image.Resampling.LANCZOS).save(out)
                    else:out=render_page(original,number,tools,work,chosen['dpi'],e['page_size'])
                    with Image.open(out) as im:
                        if list(im.size)!=size:raise ValueError('Rebuilt OCR image/TSV coordinate mismatch')
                    ref=ctx.loc(kind='pdf_region',page=number,bbox=region,page_size=e['page_size'])
                    render,_=ctx.save_bytes(out.read_bytes(),'png','original','page_render',[ref]);e['render_artifact_id']=render['id']
            sx,sy=(region[2]-region[0])/size[0],(region[3]-region[1])/size[1]
            e.update(pixel_to_page=[sx,0,0,sy,region[0],region[1]],selected_variant=choice,parser='quote_safe_tsv_1.0',reparsed_from=eid)
            replacement,_=ctx.save_bytes(json.dumps(e,ensure_ascii=False,separators=(',',':')).encode(),'json',eid,'ocr_evidence_reparsed')
            d['blocks']=[b for b in d['blocks'] if b['payload'].get('ocr_evidence_artifact_id')!=eid];ctx.serial=max((b['order'] for b in d['blocks']),default=-1)+1
            mapped=[];groups=collections.defaultdict(list)
            for word in chosen['words']:
                box=word['bbox'];bbox=[region[0]+box[0]*sx,region[1]+box[1]*sy,region[0]+box[2]*sx,region[1]+box[3]*sy]
                w=dict(word,page_bbox=bbox);mapped.append(w);center=[(bbox[0]+bbox[2])/2,(bbox[1]+bbox[3])/2]
                if any(n['x0']-1<=center[0]<=n['x1']+1 and n['top']-1<=center[1]<=n['bottom']+1 for n in native_words):continue
                groups[tuple(word['line'])].append(w)
            for words in groups.values():
                text=' '.join(w['text'] for w in words);box=[min(w['page_bbox'][0] for w in words),min(w['page_bbox'][1] for w in words),max(w['page_bbox'][2] for w in words),max(w['page_bbox'][3] for w in words)]
                spans=[];offset=0
                for w in words:spans.append({'text':w['text'],'char_start':offset,'char_end':offset+len(w['text']),'bbox':w['page_bbox'],'confidence':w['confidence']});offset+=len(w['text'])+1
                flags=[warning('ocr_tsv_reparsed','Literal quotes recovered from saved OCR output; verify OCR accuracy against the source.')]
                if any(w['confidence']<80 for w in words):flags.append(warning('ocr_low_confidence','Some OCR confidence values are below 80.'))
                ctx.block('paragraph',text,[ctx.loc(kind='pdf_region',page=number,bbox=box,page_size=e['page_size'])],payload={'ocr_evidence_artifact_id':replacement['id'],'spans':spans,'reading_order':'ocr_line'},q=quality('tesseract_lstm',statistics.mean(w['confidence'] for w in words),flags,True))
            for table in d['blocks']:
                if table['quality']['method']!='ocr_table_geometry' or table['source_refs'][0].get('page')!=number:continue
                for cell in table['payload']['cells']:
                    box=cell['source_refs'][0]['bbox'];selected=[w for w in mapped if box[0]<=(w['page_bbox'][0]+w['page_bbox'][2])/2<=box[2] and box[1]<=(w['page_bbox'][1]+w['page_bbox'][3])/2<=box[3]]
                    selected.sort(key=lambda w:(w['line'],w['bbox'][0]));cell['text']=' '.join(w['text'] for w in selected);cell['value']=cell['text'] or None;cell['type']='string' if cell['text'] else 'blank'
            for row in d['extraction']['coverage'].get('pages',[]):
                if row['page']==number:row.update(words=len(chosen['words']),confidence=chosen['confidence'],selected_dpi=chosen['dpi'],evidence=replacement['id'])
            recovered+=1
        if recovered:
            if d['source']['format']=='pdf':d['blocks'].sort(key=lambda b:(b['source_refs'][0].get('page',0),b['source_refs'][0].get('bbox',[0,0])[1],b['source_refs'][0].get('bbox',[0,0])[0],b['order']))
            for i,b in enumerate(d['blocks']):b['order']=i
            d['references']=[]
            from convert import collect_references
            collect_references(d);d['extraction']['warnings'].append(warning('ocr_tsv_reparsed',str(recovered)+' OCR outputs recovered quote-safely from retained TSV.'));d['extraction']['status']='partial'
        d['extraction']['tools']['quote_safe_tsv_parser']=PARSER_HASH;validate_document(d,root);atomic_json(path,d)
    finally:
        if pdf is not None:pdf.close()
    return d
