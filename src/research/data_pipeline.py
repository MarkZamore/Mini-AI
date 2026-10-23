"""Deterministic, source-verified research data. Never modifies the corpus.

Automatic native-span labels are deliberately weaker than independent semantic gold.
The final split is sealed; training/selection scripts must not open sealed-final/.
"""
from __future__ import annotations
import argparse, collections, hashlib, json, os, pathlib, random, re, sqlite3
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / 'src/build/research-20261024/data'
SEED = 20261024
NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
DEMO = re.compile(r'мобильност|практик|коррупц|управлен.{0,20}риск|тәуекел|ұтқыр|сыбайлас', re.I)

def norm(s): return ' '.join(s.split())
def digest(p):
    with pathlib.Path(p).open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()
def hid(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()
def write_json(p, obj): p.write_text(json.dumps(obj, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
def write_jsonl(p, rows):
    with p.open('w', encoding='utf-8', newline='\n') as f:
        for row in rows: f.write(json.dumps(row, ensure_ascii=False)+'\n')
def read_jsonl(p):
    with pathlib.Path(p).open(encoding='utf-8') as f:
        return [json.loads(line) for line in f if line.strip()]
def xml_text(node):
    return norm(''.join((n.text or '') if n.tag.endswith('}t') else ' ' if n.tag.rsplit('}',1)[-1] in ('tab','br','cr') else '' for n in node.iter()))
def lang(text):
    if len(re.findall('[әғқңөұүһіӘҒҚҢӨҰҮҺІ]', text)) >= 2: return 'kz'
    letters=re.findall(r'[^\W\d_]', text)
    return 'ru' if letters and len(re.findall('[А-Яа-яЁё]',text))/len(letters) > .6 else None
def canonical_title(s):
    s=re.sub(r'\.(?:docx?|pdf|rtf|xlsx?)$', '', s.casefold())
    s=re.sub(r'\b(?:19|20)\d{2}\b|\b(?:редакция|версия|копия)\b', '', s)
    return norm(' '.join(re.findall(r'[\w.-]+',s)))

def verified_sources(out):
    corpus=ROOT
    documents=[json.loads(p.read_text(encoding='utf-8')) for p in sorted((corpus/'db/documents').glob('*.json'))]
    old=json.loads((ROOT/'src/build/model-preparation/dataset/document-groups.json').read_text(encoding='utf-8'))
    # The prior union-find includes source equality, title/edition matches, and
    # boilerplate-filtered five-word near-duplicate shingles. Preserve its groups.
    family={d:g['group_id'] for g in old['groups'] for d in g['document_ids']}
    assert set(family)=={d['document_id'] for d in documents}, 'Family snapshot must match corpus'
    db=corpus/'db/index.sqlite'
    con=sqlite3.connect(db.as_uri()+'?mode=ro&immutable=1',uri=True)
    con.row_factory=sqlite3.Row
    accepted={}; counts=collections.Counter(); doc_meta=[]
    for d in documents:
        doc_meta.append({'document_id':d['document_id'],'family_id':family[d['document_id']],'title':d['title'],'format':d['source']['format'],'status':d['extraction']['status']})
        if d['source']['format']!='docx' or d['extraction']['status']!='complete': continue
        source=(corpus/'db'/d['source']['path']).resolve()
        if not source.is_relative_to(corpus/'docs'): raise ValueError('Source escapes corpus/docs')
        if digest(source)!=d['source_version_sha256']: raise ValueError('Original source hash mismatch')
        with zipfile.ZipFile(source) as z:
            trees={}
            for b in d['blocks']:
                q=b['quality']; text=norm(b['text'])
                if b['kind'] not in ('paragraph','heading','list_item'): continue
                if q['method']!='native' or q['needs_review'] or q['warnings']: continue
                if not 60<=len(text)<=1800 or not lang(text): continue
                if re.search(r'_{3,}|\.{5,}|\b20__\b|___',text): counts['blank_form']+=1; continue
                refs=b['source_refs']
                if not refs or any(r.get('artifact_id')!='original' or r.get('kind')!='ooxml_node' or r.get('source_sha256')!=d['source_version_sha256'] for r in refs): continue
                good=False
                for ref in refs:
                    part=ref.get('part',''); path=ref.get('xpath','')
                    if not path.startswith('/w:document/'): continue
                    if part not in trees: trees[part]=ET.fromstring(z.read(part))
                    n=trees[part].find('./'+path[len('/w:document/'):],NS)
                    if n is not None and xml_text(n)==text: good=True; break
                if not good: counts['xml_mismatch']+=1; continue
                accepted[b['id']]=(d,b,text)
    records=[]; seen=set()
    for r in con.execute('SELECT * FROM chunks ORDER BY fragment_id'):
        if r['block_id'] not in accepted: continue
        d,b,text=accepted[r['block_id']]
        if norm(r['text']) not in text or r['source_sha256']!=d['source_version_sha256']: continue
        native=norm(r['text']); fingerprint=hid(native.casefold())
        if fingerprint in seen: counts['exact_text_duplicate']+=1; continue
        seen.add(fingerprint)
        records.append({'fragment_id':r['fragment_id'],'document_id':r['document_id'],'family_id':family[r['document_id']],'block_id':r['block_id'],'language':lang(native),'title':d['title'],'text':native,'source_sha256':r['source_sha256'],'source_refs':json.loads(r['source_refs']),'verification':'machine_original_DOCX_XML_exact_match','human_verified':False,'section_path':[], 'edition':None,'effective_date':None,'demo_topic_quarantine':bool(DEMO.search(d['title']+' '+native))})
    con.close()
    write_jsonl(out/'sources.jsonl', records)
    write_json(out/'corpus-audit.json',{'seed':SEED,'corpus_documents':len(documents),'corpus_families':len(set(family.values())),'verified_native_blocks':len(accepted),'unique_verified_fragments':len(records),'eligible_documents':len(set(r['document_id'] for r in records)),'eligible_families':len(set(r['family_id'] for r in records)),'languages':dict(collections.Counter(r['language'] for r in records)),'language_label_method':'Automatic Kazakh-specific-letter/Cyrillic heuristic, not human-verified','quarantined_demo_topic_fragments':sum(r['demo_topic_quarantine'] for r in records),'rejections':dict(counts),'human_review':False,'limits':['Original DOCX only; XLSX cell supervision not yet source-verified','No semantic/manual gold','Prior grouping policy retained; cross-language family equivalence still automatic'],'source_database_sha256':digest(db),'family_snapshot_sha256':digest(ROOT/'src/build/model-preparation/dataset/document-groups.json')})
    write_jsonl(out/'document-families.jsonl',doc_meta)
    return records

def question(source):
    # The task names its actual document and asks about a literal phrase. No
    # invented procedure, absence, contradiction, date or numeric condition.
    text=source['text']; clean=re.sub(r'^\s*[\d.()–—\-]+\s*','',text)
    anchor=' '.join(clean.split()[:7]).strip(' ,;:.')
    condition=re.search(r'\b(?:в случае|при условии|если)\s+([^.;:]{12,100})',clean,re.I)
    obligation=re.search(r'\b(Работник|Работодатель|обучающийся|студент|университет)\s+(?:обязан|обязуется|должен)\s+([^.;]{12,100})',clean,re.I)
    if source['language']=='kz':
        q=f'«{source["title"]}» құжатында «{anchor}» туралы қандай талап көрсетілген? Шарттары болса, оларды сақтап, дереккөзді келтір.'
    elif obligation:
        q=f'Что обязан сделать {obligation.group(1).casefold()} в отношении «{obligation.group(2).strip()}» согласно документу «{source["title"]}»? Укажи основание и названные в нём ограничения.'
    elif condition:
        q=f'Что предусматривает документ «{source["title"]}» для случая «{condition.group(1).strip()}»? Сохрани явно указанные условия и приведи основание.'
    else:
        q=f'Что говорится в документе «{source["title"]}» о «{anchor}»? Укажи положение и источник, сохрани существенные ограничения.'
    if DEMO.search(q): return None
    return q

def make_example(source, split):
    q=question(source)
    if not q: return None
    atoms=[p.strip() for p in re.split(r';\s*',source['text']) if len(p.strip())>=15]
    if not atoms: atoms=[source['text']]
    task='explicit_condition' if re.search(r'\b(?:в случае|при условии|если)\b',source['text'],re.I) else 'explicit_obligation' if re.search(r'\b(?:обязан|обязуется|должен)\b',source['text'],re.I) else 'native_document_rule_extraction'
    return {'id':hid(source['fragment_id']+'native_question_v1'),'split':split,'intent_group':hid(source['family_id']+'\n'+source['text'].casefold()),'family_ids':[source['family_id']],'document_ids':[source['document_id']],'language':source['language'],'question':q,'positive_ids':[source['fragment_id']],'context_ids':[source['fragment_id']],'required_atoms':[{'text':a,'fragment_id':source['fragment_id'],'label':'source_verified_literal_span','semantic_completeness':'unknown'} for a in atoms],'forbidden_assertions':[],'answerability':'answerable_from_identified_literal_span','verification':'automatic_source_verified_not_independent_human_gold','task':task,'human_verified':False,'evaluation_limit':'Document/anchor-assisted question; no independently verified complete normative semantic gold'}

def allocate(records):
    byfam=collections.defaultdict(list)
    for r in records:
        if not r['demo_topic_quarantine'] and question(r): byfam[r['family_id']].append(r)
    order=sorted(byfam,key=lambda f:hid(str(SEED)+f))
    family_split={}; outputs={k:[] for k in ('final','selection','calibration','gatecheck')}
    def take(f,lang_,n,split,transfer=False):
        ss=sorted([s for s in byfam[f] if s['language']==lang_],key=lambda s:hid(str(SEED)+s['fragment_id']))[:n]
        for source in ss:
            ex=make_example(source,split);ex['thematic_transfer']=transfer;outputs[split].append(ex)
        return len(ss)
    # Coverage quotas are fixed before evaluating any model. The constrained
    # native subset has only three large bilingual families: final is therefore
    # 100 cases / eight families, not a superficially diverse 120 from two.
    main=max(order,key=lambda f:sum(s['language']=='kz' for s in byfam[f]))
    family_split[main]='final';take(main,'ru',22,'final');take(main,'kz',33,'final')
    transfer_candidates=[f for f in order if f!=main and sum(s['language']=='ru' for s in byfam[f])>=30 and any(re.search(r'70[357]|711',s['title']) for s in byfam[f])]
    if transfer_candidates:
        transfer=sorted(transfer_candidates,key=lambda f:(len(byfam[f]),hid(f)))[0]
        family_split[transfer]='final';take(transfer,'ru',30,'final',True)
    extra_candidates=[f for f in order if f not in family_split and all(s['language']=='ru' for s in byfam[f]) and len(byfam[f])>=3]
    for f,n in zip(sorted(extra_candidates,key=lambda f:(len(byfam[f]),hid(f)))[:6],(3,3,3,2,2,2)):
        family_split[f]='final';take(f,'ru',n,'final')
    # Keep the other two large bilingual families for development and training.
    candidates=[f for f in order if f not in family_split]
    dev_main=max(candidates,key=lambda f:sum(s['language']=='kz' for s in byfam[f]))
    family_split[dev_main]='development'
    # Allocate entire families to a development sub-split: no neighbouring
    # source paragraphs cross selection/calibration/gate boundaries.
    for source in sorted(byfam[dev_main],key=lambda s:hid(s['fragment_id']))[:72]:outputs['selection'].append(make_example(source,'selection'))
    available=[f for f in candidates if f!=dev_main and all(s['language']=='ru' for s in byfam[f])]
    small=sorted([f for f in available if 2<=len(byfam[f])<=8],key=lambda f:(len(byfam[f]),hid(f)))
    for f in small[:4]:
        if len(outputs['selection'])>=80:break
        family_split[f]='development';take(f,'ru',min(2,80-len(outputs['selection'])),'selection')
    for split,quota in [('calibration',20),('gatecheck',20)]:
        for f in sorted([f for f in available if f not in family_split],key=lambda f:(-len(byfam[f]),hid(f))):
            n=quota-len(outputs[split])
            if n<=0:break
            family_split[f]='development';take(f,'ru',n,split)
    for split in ('selection','calibration','gatecheck'):
        for ex in outputs[split]:ex['evidence_cluster']=ex['family_ids'][0]
    train=[]
    for f in order:
        if f not in family_split:
            family_split[f]='train'
            train.extend(make_example(s,'train') for s in byfam[f])
    outputs['train']=sorted(train,key=lambda e:hid(str(SEED)+e['id']))[:1500]
    return outputs,family_split

def build(out):
    out.mkdir(parents=True,exist_ok=True)
    records=verified_sources(out)
    datasets,assignment=allocate(records)
    sealed=out/'sealed-final'; sealed.mkdir(exist_ok=True)
    for split,rows in datasets.items(): write_jsonl((sealed if split=='final' else out)/(split+'.jsonl'),rows)
    write_json(out/'family-assignment.json',assignment)
    summaries={k:{'count':len(v),'languages':dict(collections.Counter(e['language'] for e in v)),'families':len({f for e in v for f in e['family_ids']}),'tasks':dict(collections.Counter(e['task'] for e in v)),'thematic_transfer':sum(e.get('thematic_transfer',False) for e in v)} for k,v in datasets.items()}
    expected={'final':100,'selection':80,'calibration':20,'gatecheck':20}
    report={'seed':SEED,'splits':summaries,'expected_counts':expected,'counts_met':all(summaries[k]['count']==n for k,n in expected.items()),'all_final_families_excluded_from_weight_training':True,'development_subsplits_share_families':False,'human_review':False,'claims':'Machine-verified extraction/explicit-obligation benchmark only; desired diverse semantic 120-question gold remains NOT established','unknown_semantic_labels':True,'demo_exclusion':'Conservative whole-topic quarantine in new training and development','final_sha256':digest(sealed/'final.jsonl'),'final_disclosure_policy':'Training/selection modules must not open final; the author of data is not a blind evaluator','limitations':['No independently human-reviewed labels','No fabricated no-answer/procedure/conflict labels','Automatic document/phrase anchors make this easier than ordinary questions','Final has eight source families but two dominate; weak statistical power','Native original XML subset does not represent all OCR-heavy documents']}
    write_json(out/'manifest.json',report)
    write_jsonl(out/'disclosure-log.jsonl',[{'event':'final_created_coverage_revision_before_model_evaluation','date':'2026-10-24','author':'automatic_data_pipeline','contents_used_for_model_selection':False,'note':'100 cases selected by family/language quotas; initial 120/two-family draft replaced before any model evaluation. Author creates final, so it is not fully blind.'}])
    print(json.dumps(report,ensure_ascii=True,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=pathlib.Path,default=DEFAULT_OUT);a=p.parse_args();build(a.out.resolve())
