"""Idempotent native Excel display metadata enrichment; raw values are preserved."""
import io, json, zipfile
from pathlib import Path
from lxml import etree as E
from openpyxl.styles.numbers import BUILTIN_FORMATS,is_date_format
from openpyxl.utils.datetime import from_excel,CALENDAR_MAC_1904,CALENDAR_WINDOWS_1900
from common import atomic_json, native, sha256, stored_path

ENRICHMENT_HASH=sha256(Path(__file__))
NS={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main','r':'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}

def enrich_excel(document,path,root):
    if document['source']['format'] not in {'xls','xlsx'}:return document
    stamp={'version':'1.0','code_sha256':ENRICHMENT_HASH,'source_sha256':document['source_version_sha256']}
    if document['extraction']['tools'].get('excel_display_metadata')==stamp:return document
    original=stored_path(root,document['source']['path'],allow_source=True)
    if sha256(original)!=document['source_version_sha256']:raise ValueError('Excel original changed before metadata enrichment')
    if document['source']['format']=='xls':
        import xlrd
        from openpyxl.utils.cell import range_boundaries
        book=xlrd.open_workbook(file_contents=Path(native(original)).read_bytes(),formatting_info=True)
        for block in document['blocks']:
            if block['kind']!='table' or 'sheet' not in block['payload']:continue
            sheet=book.sheet_by_name(block['payload']['sheet']);metadata={}
            for cell in block['payload']['cells']:
                row,col=cell['row'],cell['column'];xf=book.xf_list[sheet.cell_xf_index(row,col)];fmt=book.format_map[xf.format_key].format_str
                info={'style_id':sheet.cell_xf_index(row,col),'number_format':fmt,'date_system':1904 if book.datemode else 1900,'raw_text':cell['text'],'native_type':cell['type'],'native_value':sheet.cell_value(row,col)}
                if cell['type']=='boolean':cell['value']=bool(cell['value']);info['display_text']='TRUE' if cell['value'] else 'FALSE'
                if cell['type']=='error':info['display_text']=xlrd.error_text_from_code.get(cell['value'],str(cell['value']))
                if cell['type']=='excel_date_serial':
                    dt=from_excel(cell['value'],epoch=CALENDAR_MAC_1904 if book.datemode else CALENDAR_WINDOWS_1900);info['display_text']=dt.isoformat() if hasattr(dt,'isoformat') else str(dt)
                metadata[cell['id']]=info
            block['payload']['cell_formats']=metadata;block['payload']['date_system']=1904 if book.datemode else 1900
        document['extraction']['tools']['excel_display_metadata']=stamp;atomic_json(path,document);return document
    with zipfile.ZipFile(native(original)) as z:
        styles=E.fromstring(z.read('xl/styles.xml'));custom={int(n.get('numFmtId')):n.get('formatCode') for n in styles.findall('s:numFmts/s:numFmt',NS)}
        formats=[custom.get(int(n.get('numFmtId','0')),BUILTIN_FORMATS.get(int(n.get('numFmtId','0')),'General')) for n in styles.findall('s:cellXfs/s:xf',NS)]
        book=E.fromstring(z.read('xl/workbook.xml'));prop=book.find('s:workbookPr',NS);date1904=prop is not None and prop.get('date1904','0') in {'1','true'}
        links={n.get('Id'):n.get('Target') for n in E.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
        import posixpath
        parts={sh.get('name'):posixpath.normpath(posixpath.join('xl',links[sh.get('{'+NS['r']+'}id')])) for sh in book.findall('s:sheets/s:sheet',NS)}
        for block in document['blocks']:
            if block['kind']!='table' or block['payload'].get('sheet') not in parts:continue
            sheet=block['payload']['sheet'];part=parts[sheet].lstrip('/')
            if part.startswith('xl/xl/'):part=part[3:]
            catalog={}
            with z.open(part) as source:
                for _,cell in E.iterparse(source,events=('end',),tag='{'+NS['s']+'}c',resolve_entities=False,no_network=True):
                    if cell.find('s:v',NS) is not None or cell.find('s:is',NS) is not None or cell.find('s:f',NS) is not None:
                        style=int(cell.get('s','0'));catalog[cell.get('r')]={'style_id':style,'number_format':formats[style] if style<len(formats) else 'General','native_type':cell.get('t','n')}
                    cell.clear()
            metadata={}
            for cell in block['payload']['cells']:
                address=next(r['range'] for r in cell['source_refs'] if r['kind']=='sheet_range');fmt=catalog.get(address,{'style_id':0,'number_format':'General'})
                info=dict(fmt,date_system=1904 if date1904 else 1900,raw_text=cell['text'])
                cell['type']={'n':'number','s':'string','inlineStr':'string','str':'string','b':'boolean','e':'error','d':'iso_date'}.get(cell['type'],cell['type'])
                value=cell['value']
                if isinstance(value,(float,int)) and not isinstance(value,bool) and is_date_format(fmt['number_format']):
                    cell['type']='excel_date_serial'
                    if not date1904 and int(value)==60:info['display_text']='1900-02-29';info['warning']='Excel fictitious leap-day serial; original numeric value is retained.'
                    else:
                        dt=from_excel(value,epoch=CALENDAR_MAC_1904 if date1904 else CALENDAR_WINDOWS_1900)
                        info['display_text']=dt.isoformat() if hasattr(dt,'isoformat') else str(dt)
                metadata[cell['id']]=info
            block['payload']['cell_formats']=metadata;block['payload']['date_system']=1904 if date1904 else 1900
    document['extraction']['tools']['excel_display_metadata']=stamp
    atomic_json(path,document);return document
