"""Local page/region OCR, retaining coordinates and all recognition variants."""
import csv, hashlib, io, json, os, statistics, subprocess, tempfile
from pathlib import Path
from collections import defaultdict
from PIL import Image
import numpy as np
import cv2
import pdfplumber
from common import native, CREATE_NO_WINDOW, atomic_json, relative, read_json, sha256
from formats import quality, warning
OCR_CODE_HASH=sha256(Path(__file__))

def process(args,env=None,timeout=300):
    result=subprocess.run([str(a) for a in args],capture_output=True,timeout=timeout,env=env,creationflags=CREATE_NO_WINDOW)
    if result.returncode: raise RuntimeError(Path(str(args[0])).name+': '+result.stderr.decode('utf-8','replace')[-1500:])
    return result

def recognize(image_path,tools,output,dpi=300,psm=3):
    env=dict(os.environ,OMP_THREAD_LIMIT='4',OMP_NUM_THREADS='4')
    process([tools['tesseract'],image_path,output,'--tessdata-dir',tools['tessdata'],'--oem','1','-l','rus+kaz+eng','--psm',str(psm),'--dpi',str(dpi),'-c','tessedit_create_tsv=1','-c','tessedit_create_txt=0'],env)
    data=Path(str(output)+'.tsv').read_bytes()
    rows=list(csv.DictReader(io.StringIO(data.decode('utf-8-sig')),delimiter='\t'))
    words=[]
    for row in rows:
        if row.get('level')!='5' or not row.get('text','').strip(): continue
        try: confidence=float(row['conf'])
        except (ValueError,KeyError): confidence=0
        words.append({'text':row['text'],'confidence':max(0,min(100,confidence)),
                      'bbox':[int(row['left']),int(row['top']),int(row['left'])+int(row['width']),int(row['top'])+int(row['height'])],
                      'line':[int(row['block_num']),int(row['par_num']),int(row['line_num'])]})
    return {'words':words,'confidence':statistics.mean(w['confidence'] for w in words) if words else None,'dpi':dpi,'psm':psm},data

def render_page(source,page,tools,work,dpi,page_size):
    prefix=Path(work)/f'page_{page}_{dpi}'
    args=[Path(tools['poppler'])/'pdftoppm.exe','-f',page,'-l',page,'-r',dpi]
    cap=4200 if dpi==300 else 5600
    if max(page_size)*dpi/72>cap: args.extend(['-scale-to',cap])
    process(args+['-png','-singlefile',source,prefix],timeout=180)
    image=prefix.with_suffix('.png')
    if not image.exists(): raise RuntimeError('PDF renderer did not produce page image')
    return image

def direct_scan_image(page,reader_page,work,number):
    """Use an axis-aligned JPEG scan directly, avoiding an expensive PDF round trip."""
    size=[float(page.width),float(page.height)]
    if page.rotation or not page.images:return None
    images=sorted(page.images,key=lambda i:(i['x1']-i['x0'])*(i['bottom']-i['top']),reverse=True)
    image=images[0];box=[float(image['x0']),float(image['top']),float(image['x1']),float(image['bottom'])]
    if (box[2]-box[0])*(box[3]-box[1])<.8*size[0]*size[1]:return None
    matrix=None;stack=[];current=[1,0,0,1,0,0]
    def compose(left,right):
        a,b,c,d,e,f=left;g,h,i,j,k,l=right
        return [a*g+c*h,b*g+d*h,a*i+c*j,b*i+d*j,a*k+c*l+e,b*k+d*l+f]
    for operands,operator in reader_page.get_contents().operations:
        if operator==b'q':stack.append(current[:])
        elif operator==b'Q':current=stack.pop() if stack else [1,0,0,1,0,0]
        elif operator==b'cm':current=compose(current,[float(x) for x in operands])
        elif operator==b'Do' and str(operands[0]).lstrip('/')==image['name']:matrix=current[:];break
    if matrix is None or abs(matrix[1])>.0001 or abs(matrix[2])>.0001 or matrix[0]<=0 or matrix[3]<=0:return None
    try:
        bitmap=Image.open(io.BytesIO(image['stream'].get_data())).convert('RGB')
        path=Path(work)/f'direct_scan_{number}.png';bitmap.save(path)
        return {'path':path,'bbox':box,'original_pixels':list(bitmap.size)}
    except Exception:return None

def normalize_image(image_path):
    """Return rotation mapping; never replace original evidence."""
    image=Image.open(image_path).convert('RGB')
    return image

def raster_tables(ctx,image_path,words,page_num,page_size,region):
    """Preserve ruled scan tables using explicit, reviewable geometric evidence."""
    with Image.open(image_path) as im: array=np.array(im.convert('L')); width,height=im.size
    binary=cv2.adaptiveThreshold(array,255,cv2.ADAPTIVE_THRESH_MEAN_C,cv2.THRESH_BINARY_INV,31,15)
    horizontal=cv2.morphologyEx(binary,cv2.MORPH_OPEN,cv2.getStructuringElement(cv2.MORPH_RECT,(max(30,width//30),1)))
    vertical=cv2.morphologyEx(binary,cv2.MORPH_OPEN,cv2.getStructuringElement(cv2.MORPH_RECT,(1,max(20,height//30))))
    grid=cv2.bitwise_or(horizontal,vertical)
    contours,hierarchy=cv2.findContours(grid,cv2.RETR_TREE,cv2.CHAIN_APPROX_SIMPLE)
    if hierarchy is None:return
    boxes=[]
    for contour,info in zip(contours,hierarchy[0]):
        if info[3]<0:continue
        x,y,w,h=cv2.boundingRect(contour)
        if w<40 or h<15 or w*h>width*height*.25:continue
        box=(x,y,x+w,y+h)
        if any(all(abs(a-b)<=5 for a,b in zip(box,other)) for other in boxes):continue
        boxes.append(box)
    if len(boxes)<4:return
    def boundaries(values):
        result=[]
        for val in sorted(values):
            if not result or val-result[-1]>8:result.append(val)
        return result
    xs=boundaries([v for b in boxes for v in [b[0],b[2]]]);ys=boundaries([v for b in boxes for v in [b[1],b[3]]])
    sx,sy=(region[2]-region[0])/width,(region[3]-region[1])/height
    def mapped(box):return [region[0]+box[0]*sx,region[1]+box[1]*sy,region[0]+box[2]*sx,region[1]+box[3]*sy]
    bbox=[min(b[0] for b in boxes),min(b[1] for b in boxes),max(b[2] for b in boxes),max(b[3] for b in boxes)]
    cells=[];flag=warning('scan_table_inferred','Ruled table structure is inferred from image geometry; verify merged cells and reading order.')
    b=ctx.block('table','',[ctx.loc(kind='pdf_region',page=page_num,bbox=mapped(bbox),page_size=page_size)],
        payload={'rows':len(ys)-1,'columns':len(xs)-1,'cells':cells,'merges':[],'geometry':'opencv_ruled_scan'},q=quality('ocr_table_geometry',warnings=[flag],review=True))
    for box in sorted(boxes,key=lambda x:(x[1],x[0])):
        selected=[w for w in words if box[0]<=(w['bbox'][0]+w['bbox'][2])/2<=box[2] and box[1]<=(w['bbox'][1]+w['bbox'][3])/2<=box[3]]
        selected.sort(key=lambda w:(w['line'],w['bbox'][0]));text=' '.join(w['text'] for w in selected)
        col=min(range(len(xs)),key=lambda i:abs(xs[i]-box[0])); row=min(range(len(ys)),key=lambda i:abs(ys[i]-box[1]))
        endcol=min(range(len(xs)),key=lambda i:abs(xs[i]-box[2]));endrow=min(range(len(ys)),key=lambda i:abs(ys[i]-box[3]))
        cells.append({'id':b['id']+f':r{row}c{col}','row':row,'column':col,'rowspan':max(1,endrow-row),'colspan':max(1,endcol-col),'type':'string' if text else 'blank',
            'value':text or None,'text':text,'formula':None,'cached_value':None,'source_refs':[ctx.loc(kind='pdf_region',page=page_num,bbox=mapped(box),page_size=page_size)]})
    b['payload']['merges']=[{'row':c['row'],'column':c['column'],'rowspan':c['rowspan'],'colspan':c['colspan']} for c in cells if c['rowspan']>1 or c['colspan']>1]
    ctx.warn('scan_table_needs_review','Table geometry on page '+str(page_num)+' is inferred from scan grid lines.')

def ocr_page(ctx,source,page_num,page_size,native_words,tools,work,direct=None):
    cache_key=hashlib.sha256((OCR_CODE_HASH+json.dumps({n:a['sha256'] for n,a in tools['downloads'].items() if n.endswith('.traineddata')},sort_keys=True)).encode()).hexdigest()[:20]
    cache_path=ctx.root/'artifacts'/'ocr_cache'/ctx.doc['source_version_sha256']/cache_key/(str(page_num)+'.json')
    if cache_path.exists():
        cached=read_json(cache_path)
        if all((ctx.root/a['path']).exists() and sha256(ctx.root/a['path'])==a['sha256'] for a in cached['artifacts']):
            for a in cached['artifacts']:
                if not any(x['id']==a['id'] for x in ctx.doc['artifacts']): ctx.doc['artifacts'].append(a)
            ctx.doc['assets'].extend(a for a in cached['assets'] if a not in ctx.doc['assets'])
            ctx.doc['extraction']['warnings'].extend(cached['warnings'])
            for b in cached['blocks']:ctx.block(b['kind'],b['text'],b['source_refs'],role=b['role'],payload=b['payload'],q=b['quality'])
            return cached['result']
    start_blocks=len(ctx.doc['blocks']); start_warnings=len(ctx.doc['extraction']['warnings'])
    region=direct['bbox'] if direct else [0,0,*page_size]
    def image_at(dpi):
        if not direct:return render_page(source,page_num,tools,work,dpi,page_size)
        bitmap=Image.open(direct['path']); cap=4200 if dpi==300 else 5600
        target=min(cap,max(*bitmap.size,max(region[2]-region[0],region[3]-region[1])*dpi/72))
        scale=target/max(bitmap.size);bitmap=bitmap.resize((max(1,round(bitmap.width*scale)),max(1,round(bitmap.height*scale))),Image.Resampling.LANCZOS)
        path=Path(work)/f'page_{page_num}_{dpi}.png';bitmap.save(path);return path
    img=image_at(300)
    initial,tsv=recognize(img,tools,Path(work)/f'ocr_{page_num}_300',300,3)
    variants=[]
    a,_=ctx.save_bytes(tsv,'tsv','original','ocr_tsv')
    initial['artifact_id']=a['id']; variants.append(initial)
    chosen=initial; dpi=300; chosen_img=img
    weak=initial['confidence'] is not None and (initial['confidence']<90 or sum(w['confidence']<60 for w in initial['words'])>len(initial['words'])*.1)
    if weak:
        image400=image_at(400)
        second,data=recognize(image400,tools,Path(work)/f'ocr_{page_num}_400',400,6)
        a,_=ctx.save_bytes(data,'tsv','original','ocr_tsv'); second['artifact_id']=a['id']; variants.append(second)
        # Confidence is only a selection heuristic, never a correctness probability.
        if (second['confidence'] or 0)>(initial['confidence'] or 0)+2 and len(second['words'])>=len(initial['words'])*.8:
            chosen=second; dpi=400; chosen_img=image400
    image=Image.open(chosen_img); pxw,pxh=image.size; image.close()
    sx,sy=(region[2]-region[0])/pxw,(region[3]-region[1])/pxh
    render_ref=ctx.loc(kind='pdf_region',page=page_num,bbox=region,page_size=page_size)
    a,_=ctx.save_bytes(chosen_img.read_bytes(),'png','original','page_render',[render_ref])
    evidence={'page':page_num,'page_size':page_size,'selected_variant':variants.index(chosen),'variants':variants,
              'render_artifact_id':a['id'],'pixel_to_page':[sx,0,0,sy,region[0],region[1]],'orientation':'axis_aligned_scan' if direct else 'rendered_pdf_page',
              'direct_scan_original_pixels':direct['original_pixels'] if direct else None}
    ev,_=ctx.save_bytes(json.dumps(evidence,ensure_ascii=False,separators=(',',':')).encode('utf-8'),'json','original','ocr_evidence')
    grouped=defaultdict(list)
    for word in chosen['words']:
        x1,y1,x2,y2=word['bbox']; bbox=[region[0]+x1*sx,region[1]+y1*sy,region[0]+x2*sx,region[1]+y2*sy]; center=((bbox[0]+bbox[2])/2,(bbox[1]+bbox[3])/2)
        # Preserve native words, suppress only OCR spatial repeats over existing text.
        if any(n['x0']-1<=center[0]<=n['x1']+1 and n['top']-1<=center[1]<=n['bottom']+1 for n in native_words): continue
        grouped[tuple(word['line'])].append(dict(word,page_bbox=bbox))
    differing=len(variants)>1 and ' '.join(w['text'] for w in variants[0]['words'])!=' '.join(w['text'] for w in variants[1]['words'])
    for line,words in grouped.items():
        text=' '.join(w['text'] for w in words); bbox=[min(w['page_bbox'][0] for w in words),min(w['page_bbox'][1] for w in words),max(w['page_bbox'][2] for w in words),max(w['page_bbox'][3] for w in words)]
        confidence=statistics.mean(w['confidence'] for w in words); flags=[]
        if any(w['confidence']<80 for w in words): flags.append(warning('ocr_low_confidence','OCR includes words with confidence below 80; verify source image.'))
        if differing: flags.append(warning('ocr_variants_differ','300/400 DPI OCR variants disagree; both are retained.'))
        offset=0; spans=[]
        for w in words:
            spans.append({'text':w['text'],'char_start':offset,'char_end':offset+len(w['text']),'bbox':w['page_bbox'],'confidence':w['confidence']}); offset+=len(w['text'])+1
        ctx.block('paragraph',text,[ctx.loc(kind='pdf_region',page=page_num,bbox=bbox,page_size=page_size)],
                  payload={'ocr_evidence_artifact_id':ev['id'],'spans':spans,'reading_order':'ocr_line'},q=quality('tesseract_lstm',confidence,flags,bool(flags)))
    if weak or differing: ctx.warn('ocr_needs_review','Page '+str(page_num)+' contains low-confidence or disagreeing OCR. Scores are not correctness probabilities.')
    raster_tables(ctx,chosen_img,chosen['words'],page_num,page_size,region)
    result={'page':page_num,'words':len(chosen['words']),'selected_dpi':dpi,'confidence':chosen['confidence'],'variants':len(variants),'evidence':ev['id']}
    relevant={ev['id'],a['id']}|{v['artifact_id'] for v in variants}
    atomic_json(cache_path,{'blocks':ctx.doc['blocks'][start_blocks:],'warnings':ctx.doc['extraction']['warnings'][start_warnings:],
        'artifacts':[x for x in ctx.doc['artifacts'] if x['id'] in relevant],
        'assets':[x for x in ctx.doc['assets'] if any(r.get('page')==page_num for r in x['source_refs'])],'result':result})
    return result

def extract_pdf(ctx,source,tools,work):
    import shutil
    from pypdf import PdfReader
    staged=Path(work)/'source.pdf'; shutil.copyfile(native(source),staged)
    reader=PdfReader(staged)
    coverage=[]
    with pdfplumber.open(str(staged)) as pdf:
        for number,page in enumerate(pdf.pages,1):
            before=len(ctx.doc['blocks']); size=[float(page.width),float(page.height)]
            words=page.extract_words(x_tolerance=2,y_tolerance=3,keep_blank_chars=False); lines=defaultdict(list)
            native_text=' '.join(w['text'] for w in words)
            damaged=native_text.count('\ufffd')>max(2,len(native_text)*.01)
            if damaged: ctx.warn('pdf_encoding_damaged','Native text encoding needs OCR review on page '+str(number))
            for w in words: lines[round(w['top']/3)*3].append(w)
            for top,row in sorted(lines.items()):
                row.sort(key=lambda w:w['x0']); bbox=[min(w['x0'] for w in row),min(w['top'] for w in row),max(w['x1'] for w in row),max(w['bottom'] for w in row)]
                text=' '.join(w['text'] for w in row)
                ctx.block('paragraph',text,[ctx.loc(kind='pdf_region',page=number,bbox=bbox,page_size=size)],
                          payload={'reading_order':'geometric_heuristic'},q=quality('pdf_text_layer',warnings=[warning('reading_order_heuristic','PDF line ordering is inferred from geometry.')],review=damaged))
            try:
                tables=page.find_tables()
                for table in tables:
                    values=table.extract(); cells=[]
                    b=ctx.block('table','',[ctx.loc(kind='pdf_region',page=number,bbox=list(table.bbox),page_size=size)],
                        payload={'rows':len(values),'columns':max((len(r) for r in values),default=0),'cells':cells,'merges':[]},
                        q=quality('pdf_geometry',warnings=[warning('pdf_table_inferred','Table cells inferred from page geometry; inspect source.')],review=True))
                    for row,values_row in enumerate(values):
                        for col,value in enumerate(values_row):
                            box=table.rows[row].cells[col] if col<len(table.rows[row].cells) else None
                            if box is None: continue
                            cells.append({'id':b['id']+f':r{row}c{col}','row':row,'column':col,'rowspan':1,'colspan':1,'type':'string' if value else 'blank',
                                'value':value,'text':value or '', 'formula':None,'cached_value':None,
                                'source_refs':[ctx.loc(kind='pdf_region',page=number,bbox=list(box),page_size=size)]})
            except Exception as exc: ctx.warn('pdf_table_detection',f'Page {number}: {exc}')
            uncovered=False
            for img in page.images:
                box=[float(img['x0']),float(img['top']),float(img['x1']),float(img['bottom'])]
                area=max(0,box[2]-box[0])*max(0,box[3]-box[1])/(size[0]*size[1])
                inside=[w for w in words if box[0]<=w['x0']<=box[2] and box[1]<=w['top']<=box[3]]
                bins={int(10*(w['top']-box[1])/max(1,box[3]-box[1])) for w in inside}
                if area>.15 and (len(inside)<40 or len(bins)<4): uncovered=True
            needs_ocr=len(native_text.strip())<40 or uncovered or damaged
            row={'page':number,'native_characters':len(native_text),'ocr':False,'images':len(page.images),'rotation':page.rotation}
            if needs_ocr:
                try:
                    direct=direct_scan_image(page,reader.pages[number-1],work,number)
                    row.update(ocr_page(ctx,staged,number,size,[] if damaged else words,tools,work,direct)); row['ocr']=True
                except Exception as exc:
                    ctx.warn('ocr_failed',f'Page {number}: {exc}'); row['error']=str(exc)
                    ctx.block('unparsed','',[ctx.loc(kind='pdf_region',page=number,bbox=[0,0,*size],page_size=size)],
                              payload={'reason':str(exc)},q=quality('unprocessed',warnings=[warning('ocr_failed',str(exc))],review=True))
            row['blocks']=len(ctx.doc['blocks'])-before; coverage.append(row)
            if number%10==0 or number==len(pdf.pages): print(json.dumps({'document':ctx.doc['document_id'],'pdf_page':number,'pdf_pages':len(pdf.pages),'ocr_pages':sum(r['ocr'] for r in coverage)}),flush=True)
            page.close()
    ctx.doc['extraction']['coverage']['pages']=coverage

def ocr_embedded_images(ctx,tools,work):
    """OCR large raster attachments locally, retaining the structural image locator."""
    seen=set()
    for block in list(ctx.doc['blocks']):
        if block['kind']!='image': continue
        aid=block['payload'].get('artifact_id')
        if not aid or aid in seen: continue
        seen.add(aid); art=next(a for a in ctx.doc['artifacts'] if a['id']==aid)
        path=ctx.root/art['path']
        try:
            with Image.open(path) as image:
                width,height=image.size
                if width<150 or height<80: continue
                pixels=image.convert('RGB'); out=Path(work)/('embedded_'+art['sha256'][:12]+'.png'); pixels.save(out)
            result,data=recognize(out,tools,Path(work)/('embedded_'+art['sha256'][:12]),300,11)
            evidence,_=ctx.save_bytes(data,'tsv',aid,'ocr_tsv')
            if not result['words']: continue
            text=' '.join(w['text'] for w in result['words']); confidence=result['confidence']; flags=[warning('embedded_image_ocr','Image OCR remains machine-extracted; inspect the original asset.')]
            ctx.block('paragraph',text,block['source_refs'],block['id'],'image_text',{'spans':result['words'],'image_size':[width,height],'ocr_evidence_artifact_id':evidence['id']},quality('tesseract_lstm',confidence,flags,True))
        except Exception as exc: ctx.warn('image_ocr_unavailable',art['path']+': '+str(exc))
