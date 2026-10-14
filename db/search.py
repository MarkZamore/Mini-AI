"""Rebuildable SQLite index and read-only local API for any model runner."""
import argparse, hashlib, json, os, re, sqlite3, sys, unicodedata
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote,urlsplit
from common import DB_ROOT, read_json, sha256, native, stored_path, utc, atomic_json
import snowballstemmer
STEMMER=snowballstemmer.stemmer('russian')

def tokens(text): return re.findall(r'[^\W_]+',unicodedata.normalize('NFC',text).casefold(),re.U)
def stemmed(text):
    return ' '.join(STEMMER.stemWord(t) if re.fullmatch(r'[а-яё]+',t) else t for t in tokens(text))
def normalize_code(text):
    text=unicodedata.normalize('NFC',text).upper().replace('KAЗНИТУ','КАЗНИТУ')
    return ''.join(c for c in text if c.isalnum())

def split_text(text,limit=2000):
    start=0
    while start<len(text):
        end=min(start+limit,len(text))
        if end<len(text):
            space=text.rfind(' ',start+limit//2,end)
            if space>start: end=space
        yield start,end,text[start:end]
        start=end
        while start<len(text) and text[start].isspace(): start+=1

def fragments(document):
    blocks=document['blocks']; index=0
    while index<len(blocks):
        block=blocks[index]; index+=1
        if block['kind']=='paragraph' and block['text'].strip() and block['source_refs'][0]['kind']=='pdf_region':
            group=[block]; count=len(block['text'])
            while index<len(blocks):
                nxt=blocks[index]; prev=group[-1]; r=prev['source_refs'][0]; nr=nxt['source_refs'][0]
                if nxt['kind']!='paragraph' or not nxt['text'].strip() or nr['kind']!='pdf_region' or nr.get('page')!=r.get('page') or nxt['parent_id']!=block['parent_id'] or nxt['quality']['method']!=block['quality']['method']:break
                if count+1+len(nxt['text'])>2000 or abs(nr['bbox'][0]-r['bbox'][0])>18 or nr['bbox'][1]-r['bbox'][3]>18 or nr['bbox'][1]<r['bbox'][1]:break
                group.append(nxt);count+=1+len(nxt['text']);index+=1
            if len(group)>1:
                text='\n'.join(b['text'] for b in group); refs=[r for b in group for r in b['source_refs']]; spans=[];offset=0
                for b in group:
                    spans.append({'block_id':b['id'],'char_start':offset,'char_end':offset+len(b['text'])});offset+=len(b['text'])+1
                combined=dict(block);q=dict(block['quality']);q['warnings']=[w for b in group for w in b['quality']['warnings']];q['needs_review']=any(b['quality']['needs_review'] for b in group);combined['quality']=q
                yield combined,':group',text,refs,{'presentation':'joined_source_lines','block_spans':spans}
                continue
        if block['kind']=='table':
            rows=defaultdict(list)
            display=block['payload'].get('cell_formats',{})
            for cell in block['payload']['cells']:
                if cell['text']: rows[cell['row']].append(cell)
            for row,cells in sorted(rows.items()):
                text=' | '.join(display.get(c['id'],{}).get('display_text',c['text']) for c in sorted(cells,key=lambda c:c['column']))
                for start,end,piece in split_text(text):
                    yield block, f':row:{row}:{start}',piece,[r for c in cells for r in c['source_refs']],{'presentation':'table_row','row':row,'cell_ids':[c['id'] for c in cells],'char_start':start,'char_end':end}
        elif block['text'].strip():
            for start,end,piece in split_text(block['text']):
                yield block, f':text:{start}',piece,block['source_refs'],{'presentation':'extracted_text','char_start':start,'char_end':end}

def build_index(root=DB_ROOT):
    root=Path(root); tmp=root/'index.building.sqlite'
    from postprocess import postprocess_all
    postprocess_all(root,workers=4)
    if tmp.exists(): tmp.unlink()
    con=sqlite3.connect(tmp)
    con.executescript('''
      PRAGMA user_version=1;
      CREATE TABLE documents(document_id TEXT PRIMARY KEY,title TEXT,source_sha256 TEXT,source_path TEXT,json_path TEXT,status TEXT,sections TEXT,warnings TEXT);
      CREATE INDEX source_versions ON documents(source_sha256);
      CREATE TABLE document_sections(document_id TEXT,section TEXT,PRIMARY KEY(document_id,section));
      CREATE TABLE blocks(block_id TEXT PRIMARY KEY,document_id TEXT,kind TEXT,parent_id TEXT,position INTEGER,text TEXT,source_refs TEXT,quality TEXT,payload TEXT);
      CREATE INDEX document_blocks ON blocks(document_id,position);
      CREATE TABLE chunks(rowid INTEGER PRIMARY KEY,fragment_id TEXT UNIQUE,document_id TEXT,block_id TEXT,title TEXT,codes TEXT,text TEXT,stemmed TEXT,source_sha256 TEXT,source_refs TEXT,quality TEXT,span TEXT,warnings TEXT);
      CREATE INDEX document_chunks ON chunks(document_id);
      CREATE VIRTUAL TABLE chunks_fts USING fts5(title,codes,text,stemmed,content='chunks',content_rowid='rowid',tokenize='unicode61 remove_diacritics 0');
      CREATE TABLE edges(id INTEGER PRIMARY KEY,source_id TEXT,target_id TEXT,relation TEXT,block_id TEXT,evidence TEXT,source_refs TEXT,candidates TEXT);
      CREATE INDEX outgoing ON edges(source_id,relation);
      CREATE INDEX incoming ON edges(target_id,relation);
      CREATE TABLE artifacts(document_id TEXT,artifact_id TEXT,path TEXT,sha256 TEXT,derived_from TEXT,PRIMARY KEY(document_id,artifact_id));
      CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT);
    ''')
    documents=[]; code_map=defaultdict(set); url_map={}; stats={'documents':0,'blocks':0,'fragments':0,'confirmed_edges':0,'unresolved_references':0}
    dumps=lambda v:json.dumps(v,ensure_ascii=False,separators=(',',':'))
    for path in sorted((root/'documents').glob('*.json')):
        d=read_json(path)
        if any('ocr_evidence_artifact_id' in b['payload'] for b in d['blocks']):
            from repair_ocr import repair_document
            d=repair_document(d,path,root)
        if d['source']['format'] in {'xls','xlsx'}:
            from enrich import enrich_excel
            d=enrich_excel(d,path,root)
        from quality_finalize import finalize_document
        d=finalize_document(d,path,root)
        documents.append(d); did=d['document_id']; source=d['source']; warnings=d['extraction']['warnings']
        con.execute('INSERT INTO documents VALUES(?,?,?,?,?,?,?,?)',(did,d['title'],d['source_version_sha256'],source['path'],str(path.relative_to(root)).replace('\\','/'),d['extraction']['status'],dumps(source['sections']),dumps(warnings)))
        con.executemany('INSERT INTO document_sections VALUES(?,?)',[(did,s) for s in source['sections']])
        for url in source['urls']:
            url_map[unquote(url).rstrip('/')]=did
        match=re.search(r'(?i)\b(?:ДП|Ф|СТ|П)\s+(?:КазНИТУ|KaзНИТУ|НИЛАС|ЛЦТМ)\s*\d+(?:[.\-–]\d+)*',d['title'])
        codes=match.group(0) if match else ''
        if codes: code_map[normalize_code(codes)].add(did)
        for a in d['artifacts']:
            con.execute('INSERT INTO artifacts VALUES(?,?,?,?,?)',(did,a['id'],a['path'],a['sha256'],a['derived_from']))
        for b in d['blocks']:
            con.execute('INSERT INTO blocks VALUES(?,?,?,?,?,?,?,?,?)',(b['id'],did,b['kind'],b['parent_id'],b['order'],b['text'],dumps(b['source_refs']),dumps(b['quality']),dumps(b['payload'])))
            stats['blocks']+=1
        for b,suffix,text,refs,span in fragments(d):
            fid=hashlib.sha256((b['id']+suffix).encode()).hexdigest()
            fragment_warnings=list(b['quality']['warnings'])
            if d['extraction']['status']!='complete':fragment_warnings.append({'code':'document_'+d['extraction']['status'],'message':'Document has extraction limitations; inspect canonical JSON warnings and the cited source.'})
            con.execute('INSERT INTO chunks(fragment_id,document_id,block_id,title,codes,text,stemmed,source_sha256,source_refs,quality,span,warnings) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                        (fid,did,b['id'],d['title'],codes+' '+normalize_code(codes),text,stemmed(text),d['source_version_sha256'],dumps(refs),dumps(b['quality']),dumps(span),dumps(fragment_warnings)))
            stats['fragments']+=1
        stats['documents']+=1
    for d in documents:
        seen=set()
        for ref in d['references']:
            if ref['kind']=='url':
                target=url_map.get(unquote(ref['value']).rstrip('/')); candidates=[target] if target else []
                number=re.search(r'/download/document/(\d+)/',unquote(ref['value']))
                if number and not candidates and urlsplit(ref['value']).hostname=='official.satbayev.university':
                    target='satbayev:document:'+number.group(1)
                    if any(x['document_id']==target for x in documents): candidates=[target]
            else: candidates=sorted(code_map.get(normalize_code(ref['value']),set())-{d['document_id']})
            target=candidates[0] if len(candidates)==1 else None
            key=(ref['block_id'],target,ref['value'])
            if key in seen: continue
            seen.add(key)
            con.execute('INSERT INTO edges(source_id,target_id,relation,block_id,evidence,source_refs,candidates) VALUES(?,?,?,?,?,?,?)',
                (d['document_id'],target,'references',ref['block_id'],ref['evidence'],dumps(ref['source_refs']),dumps(candidates)))
            stats['confirmed_edges' if target else 'unresolved_references']+=1
    con.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')")
    con.execute('INSERT INTO metadata VALUES(?,?)',('built_at',utc()))
    con.execute('INSERT INTO metadata VALUES(?,?)',('statistics',dumps(stats)))
    con.commit()
    if con.execute('PRAGMA integrity_check').fetchone()[0]!='ok': raise ValueError('SQLite integrity check failed')
    con.close(); os.replace(tmp,root/'index.sqlite'); return stats

class Database:
    def __init__(self,db=DB_ROOT):
        self.root=Path(db).resolve(); path=self.root/'index.sqlite'
        self.connection=sqlite3.connect(path.as_uri()+'?mode=ro',uri=True); self.connection.row_factory=sqlite3.Row
    def close(self): self.connection.close()
    def __enter__(self): return self
    def __exit__(self,*args): self.close()
    def decode(self,row):
        result=dict(row)
        for key in ['source_refs','quality','span','warnings','sections','candidates','payload']:
            if key in result: result[key]=json.loads(result[key])
        return result
    def search(self,query,limit=10,document_ids=None,sections=None):
        words=tokens(query)
        if not words: return []
        pieces=[]
        for word in words[:30]:
            base=stemmed(word)
            pieces.append('("'+word+'" OR stemmed:"'+base+'")')
        params=[' AND '.join(pieces)]; filters=[]
        if document_ids:
            filters.append('c.document_id IN ('+','.join('?' for _ in document_ids)+')'); params.extend(document_ids)
        if sections:
            filters.append('c.document_id IN (SELECT document_id FROM document_sections WHERE section IN ('+','.join('?' for _ in sections)+'))'); params.extend(sections)
        sql='SELECT c.*,bm25(chunks_fts,6.0,10.0,1.0,0.5) AS score FROM chunks_fts JOIN chunks c ON c.rowid=chunks_fts.rowid WHERE chunks_fts MATCH ?'
        if filters: sql+=' AND '+' AND '.join(filters)
        sql+=' ORDER BY score,c.rowid LIMIT ?'; params.append(min(100,max(1,int(limit))))
        results=[]
        for row in self.connection.execute(sql,params):
            result=self.decode(row)
            for field in ['rowid','stemmed','codes']: result.pop(field,None)
            results.append(result)
        return results
    def get_fragment(self,fragment_id):
        row=self.connection.execute('SELECT * FROM chunks WHERE fragment_id=?',(fragment_id,)).fetchone()
        if row is None: raise KeyError(fragment_id)
        result=self.decode(row)
        for key in ['rowid','stemmed','codes']: result.pop(key,None)
        return result
    def neighbors(self,document_id,relation=None,limit=20):
        params=[document_id,document_id]; sql='SELECT e.*,b.quality AS quality FROM edges e JOIN blocks b ON b.block_id=e.block_id WHERE e.target_id IS NOT NULL AND (e.source_id=? OR e.target_id=?)'
        if relation: sql+=' AND e.relation=?'; params.append(relation)
        sql+=' ORDER BY e.id LIMIT ?'; params.append(min(100,max(1,int(limit))))
        return [self.decode(row) for row in self.connection.execute(sql,params)]
    def resolve_source(self,source_ref):
        row=self.connection.execute('SELECT * FROM documents WHERE source_sha256=? LIMIT 1',(source_ref['source_sha256'],)).fetchone()
        if row is None: raise ValueError('Unknown source version')
        original=stored_path(self.root,row['source_path'],allow_source=True)
        if sha256(original)!=source_ref['source_sha256']: raise ValueError('Original changed; locator belongs to an older version')
        aid=source_ref['artifact_id']; path=original; chain=[]; visited=set()
        while aid!='original':
            if aid in visited: raise ValueError('Artifact cycle')
            visited.add(aid)
            a=self.connection.execute('SELECT * FROM artifacts WHERE document_id=? AND artifact_id=?',(row['document_id'],aid)).fetchone()
            if a is None: raise ValueError('Unknown artifact '+aid)
            ap=stored_path(self.root,a['path'])
            if sha256(ap)!=a['sha256']: raise ValueError('Derived artifact changed')
            if not chain: path=ap
            chain.append(dict(a)); aid=a['derived_from']
        return {'document_id':row['document_id'],'original_path':str(original),'path':str(path),'source_sha256':source_ref['source_sha256'],'locator':source_ref,'derivation_chain':chain,'verified':True}

def search(query,limit=10,document_ids=None,sections=None,db=DB_ROOT):
    with Database(db) as base:return base.search(query,limit,document_ids,sections)
def get_fragment(fragment_id,db=DB_ROOT):
    with Database(db) as base:return base.get_fragment(fragment_id)
def neighbors(document_id,relation=None,limit=20,db=DB_ROOT):
    with Database(db) as base:return base.neighbors(document_id,relation,limit)
def resolve_source(source_ref,db=DB_ROOT):
    with Database(db) as base:return base.resolve_source(source_ref)

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--db',default=str(DB_ROOT)); sub=parser.add_subparsers(dest='command',required=True)
    s=sub.add_parser('search'); s.add_argument('query'); s.add_argument('--limit',type=int,default=10); s.add_argument('--document-id',action='append'); s.add_argument('--section',action='append')
    g=sub.add_parser('get'); g.add_argument('fragment_id')
    n=sub.add_parser('neighbors'); n.add_argument('document_id'); n.add_argument('--relation'); n.add_argument('--limit',type=int,default=20)
    r=sub.add_parser('resolve'); r.add_argument('source_ref_json',help='JSON locator or path to JSON containing a locator')
    sub.add_parser('rebuild'); args=parser.parse_args()
    if args.command=='rebuild':result=build_index(Path(args.db))
    else:
        with Database(args.db) as base:
            if args.command=='search':result=base.search(args.query,args.limit,args.document_id,args.section)
            elif args.command=='get':result=base.get_fragment(args.fragment_id)
            elif args.command=='neighbors':result=base.neighbors(args.document_id,args.relation,args.limit)
            else:result=base.resolve_source(json.loads(args.source_ref_json) if args.source_ref_json.lstrip().startswith('{') else read_json(args.source_ref_json))
    print(json.dumps(result,ensure_ascii=False,allow_nan=False))

if __name__=='__main__':sys.stdout.reconfigure(encoding='utf-8');main()
