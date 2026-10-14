"""Read-only Python reference generator, run with -B. Outputs only under src/build."""
import sys, json, re, random, unicodedata, hashlib
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'db/_runtime/python'))
sys.path.insert(0,str(ROOT/'db'))
import search

target=ROOT/'src/build/test-results/search-parity'
target.mkdir(parents=True,exist_ok=True)
report=json.loads((ROOT/'db/report.json').read_text(encoding='utf-8'))
cases=[]
with search.Database(ROOT/'db') as base:
    base.connection.execute('PRAGMA query_only=ON')
    base.connection.execute('PRAGMA temp_store=MEMORY')
    for item in report['search_benchmark']['queries']:
        query=item['query']
        cases.append({'query':query,'limit':10,'document_ids':[],'sections':[],'expected':base.search(query)})
    extra=['','___ !!!','Қазақ Ә Ғ Қ Ң Ө Ұ Ү Һ І','Қазақстан','құжат','ұйым','СТ КазНИТУ 401-02','МАГИСТРАНТЫ Ёлки','Straße İSTANBUL Kelvin ① 𝟠','А\u0306кадемическая Политика', 'x '*35]
    for query in extra:
        cases.append({'query':query,'limit':10,'document_ids':[],'sections':[],'expected':base.search(query)})
    hit=cases[0]['expected'][0]
    section=base.connection.execute('SELECT section FROM document_sections WHERE document_id=? LIMIT 1',(hit['document_id'],)).fetchone()[0]
    for limit,dids,sections in [(0,[hit['document_id']],[]),(101,[],[section]),(10,[hit['document_id']],[section]),(10,['missing'],[]),(10,[],['missing'])]:
        cases.append({'query':cases[0]['query'],'limit':limit,'document_ids':dids,'sections':sections,'expected':base.search(cases[0]['query'],limit,dids,sections)})
    (target/'search.json').write_text(json.dumps(cases,ensure_ascii=False,allow_nan=False),encoding='utf-8')
    corpus=set()
    for row in base.connection.execute('SELECT text FROM chunks'):
        corpus.update(w for w in search.tokens(row[0]) if re.fullmatch('[а-яё]+',w))
    with (target/'russian.jsonl').open('w',encoding='utf-8') as output:
        for word in sorted(corpus):
            output.write(json.dumps([word,search.STEMMER.stemWord(word)],ensure_ascii=False)+'\n')
    print('Russian corpus tokens:',len(corpus),'Search cases:',len(cases),'SQLite:',base.connection.execute('SELECT sqlite_version()').fetchone()[0])

# Include every assigned canonical decomposition and randomized combining/blocking cases.
normalization=['A\u0301\u0327','\u1100\u1161\u11A8','\u0130 Straße ① Ⅷ 𐐀','\u0301\u0300a','a\u0315\u0300']
decomposable=[]
for cp in range(0x110000):
    decomposition=unicodedata.decomposition(chr(cp))
    if decomposition and not decomposition.startswith('<'):
        sequence=''.join(chr(int(v,16)) for v in decomposition.split());normalization.extend([chr(cp),sequence]);decomposable.append(sequence)
rng=random.Random(1337)
marks=[chr(cp) for cp in range(0x300,0x370)]
for _ in range(5000):
    normalization.append(rng.choice(['a','е','І','қ','\u1100','\u0301',rng.choice(decomposable)])+''.join(rng.choices(marks,k=rng.randrange(0,5))))
with (target/'normalization.jsonl').open('w',encoding='utf-8') as output:
    for value in normalization:
        output.write(json.dumps([value,unicodedata.normalize('NFC',value),search.tokens(value)],ensure_ascii=False)+'\n')
print('Normalization cases:',len(normalization),'Unicode:',unicodedata.unidata_version)
