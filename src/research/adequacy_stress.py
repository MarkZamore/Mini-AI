"""Coverage calibration + external-topic stress; stress absence labels UNKNOWN.

No final data are opened. Scores are relevance diagnostics, not probabilities.
"""
import argparse,collections,json,pathlib,re,sqlite3,time
import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer
from data_pipeline import ROOT,DEFAULT_OUT,read_jsonl,write_json,write_jsonl,digest,hid
from e5_train import MODELS,BASE
from bge_reranker import OUT as BGE

STRESS=[
 ('ru','Как зарегистрировать космический корабль на Марсе по регламенту XR-ZX-2099-88113?', 'XR-ZX-2099-88113'),
 ('ru','Какие правила кормления драконов установлены стандартом DRAGON-NO-447799?', 'DRAGON-NO-447799'),
 ('ru','Как оформить аренду лунного кратера по положению MOON-LEASE-778833?', 'MOON-LEASE-778833'),
 ('ru','Какие требования к телепортации сотрудников содержит приказ TELEPORT-660099?', 'TELEPORT-660099'),
 ('ru','Какова инструкция по ремонту двигателя межзвёздного корабля?',None),
 ('ru','Как приготовить безглютеновый лимонный пирог?',None),
 ('ru','Какие правила регистрации подводной шахматной федерации действуют в Антарктиде?',None),
 ('ru','Как заменить кристалл в квантовом хронографе?',None),
 ('ru','Что требуется для лицензирования портала в параллельную вселенную?',None),
 ('ru','Как настроить радиосвязь с аппаратом возле Проксимы Центавра?',None),
 ('ru','Какое наказание предусмотрено положением ZXQ-FAKE-928774 о похищении звёзд?', 'ZXQ-FAKE-928774'),
 ('ru','Как подать заявку на регистрацию вымышленного вещества по стандарту QZX-772911?', 'QZX-772911'),
 ('kz','Марстағы ғарыш кемесін тіркеуге қандай талаптар қойылған?',None),
 ('kz','Ай кратерін жалға алу үшін қандай құжат қажет?',None),
 ('kz','Қызметкерді телепортациялау тәртібі TELEPORT-KZ-668811 ережесінде қалай берілген?', 'TELEPORT-KZ-668811'),
 ('kz','Айдаһарларды күтуге арналған DRAGON-KZ-448822 стандарты нені талап етеді?', 'DRAGON-KZ-448822'),
 ('kz','Жұлдызаралық кеменің қозғалтқышын қалай жөндеу керек?',None),
 ('kz','Лимон бәлішін қандай рецептпен дайындауға болады?',None),
 ('kz','Параллель әлемге өтетін порталға қандай лицензия керек?',None),
 ('kz','Кванттық сағаттың кристалын қалай ауыстыру керек?',None),
]
STOP=set('как какие какой какова каков чтобы согласно требуется содержит предусмотрено правила порядок документ документе какие бұл қандай қалай үшін туралы керек қажет берілген бар'.split())

def session(path):
    opts=ort.SessionOptions();opts.intra_op_num_threads=4;opts.inter_op_num_threads=1
    return ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider'])
def inputs(tok,texts):
    d=tok(texts,padding=True,truncation=True,max_length=256,return_tensors='np')
    if 'token_type_ids' not in d:d['token_type_ids']=np.zeros_like(d['input_ids'])
    return {k:np.asarray(v,dtype=np.int64) for k,v in d.items()}

def run(args):
    index=ROOT/f'src/build/research-20261024/rag-index-{args.variant}'
    im=json.loads((index/'index.json').read_text());metadata=read_jsonl(index/'fragments.jsonl');positions={r['fragment_id']:i for i,r in enumerate(metadata)}
    vectors=np.memmap(index/'vectors.f32',dtype='<f4',mode='r',shape=(im['rows'],384))
    etok=AutoTokenizer.from_pretrained(MODELS/args.variant/'onnx/tokenizer',local_files_only=True)
    es=session(MODELS/args.variant/'onnx/encoder.onnx')
    btok=AutoTokenizer.from_pretrained(BGE/'onnx/tokenizer',local_files_only=True);bs=session(BGE/'onnx/reranker.onnx')
    con=sqlite3.connect((ROOT/'db/index.sqlite').as_uri()+'?mode=ro&immutable=1',uri=True);con.row_factory=sqlite3.Row
    calibration=read_jsonl(DEFAULT_OUT/('gatecheck.jsonl' if args.gate else 'calibration.jsonl'))
    cases=[{'id':e['id'],'question':e['question'],'language':e['language'],'kind':'positive_calibration','gold_ids':e['positive_ids'],'whole_corpus_answerability':'known_native_span'} for e in calibration]
    if not args.gate:cases += [{'id':hid('stress'+q),'question':q,'language':l,'kind':'external_stress','mandatory_literal_identifier':code,'whole_corpus_answerability':'unknown_not_gold_noanswer'} for l,q,code in STRESS]
    policy_path=ROOT/f'src/build/research-20261024/adequacy-{args.variant}/policy-candidate.json'
    if args.gate:
        policy=json.loads(policy_path.read_text())
        assert policy['adequate_logit_min']==0 and policy['insufficient_logit_max']==-2.5
    result=[]
    for i,c in enumerate(cases):
        emb=es.run(['embedding'],inputs(etok,['query: '+c['question']]))[0][0]
        cos=np.asarray(vectors@emb);top=np.argsort(-cos,kind='stable')[:3]
        source_rows=[dict(con.execute('SELECT fragment_id,document_id,title,text FROM chunks WHERE fragment_id=?',(metadata[j]['fragment_id'],)).fetchone()) for j in top]
        pairs=[[c['question'],s['text']] for s in source_rows]
        scores=bs.run(['scores'],inputs(btok,pairs))[0]
        words=sorted({w for w in re.findall(r'\w+',c['question'].casefold()) if len(w)>=4 and w not in STOP})
        expr=' OR '.join('"'+w+'"' for w in words)
        lexical=[]
        if expr:
            lexical=[dict(r) for r in con.execute('SELECT fragment_id,document_id,bm25(chunks_fts) AS score FROM chunks_fts JOIN chunks ON chunks.rowid=chunks_fts.rowid WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts) LIMIT 3',(expr,))]
        code=c.get('mandatory_literal_identifier');code_present=None
        if code:code_present=con.execute('SELECT 1 FROM chunks WHERE text LIKE ? OR title LIKE ? LIMIT 1',('%'+code+'%','%'+code+'%')).fetchone() is not None
        known=[float(cos[positions[p]]) for p in c.get('gold_ids',[]) if p in positions]
        mode='insufficient_candidate' if code_present is False else 'adequate_candidate' if max(scores)>=0 else 'insufficient_candidate' if max(scores)<=-2.5 else 'clarify_candidate'
        result.append(c|{'top_cosine':float(cos[top[0]]),'gold_positive_cosine':known,'top3_bge_logits':[float(s) for s in scores],'max_top3_bge_logit':float(max(scores)),'policy_mode':mode,'dense_source_ids':[s['fragment_id'] for s in source_rows],'lexical_top3':lexical,'lexical_support_definition':'Diagnostic OR of literal content tokens; not production assistant query normalizer','mandatory_identifier_present_in_search_index':code_present,'semantic_sufficiency':'unknown','human_review':False})
        print(json.dumps({'case':i+1,'total':len(cases),'kind':c['kind'],'cosine':float(cos[top[0]]),'max_bge_logit':float(max(scores))}),flush=True)
    con.close();out=ROOT/f'src/build/research-20261024/adequacy-{args.variant}';out.mkdir(parents=True,exist_ok=True)
    def describe(rows,key):
        a=[r[key] for r in rows];return {'min':float(np.min(a)),'p05':float(np.quantile(a,.05)),'median':float(np.median(a)),'p95':float(np.quantile(a,.95)),'max':float(np.max(a))}
    kinds=sorted({r['kind'] for r in result})
    summary={'variant':args.variant,'split':'gatecheck' if args.gate else 'calibration_plus_stress','positive_calibration_count':len(calibration),'external_stress_count':0 if args.gate else len(STRESS),'human_review':False,'final_read':False,'whole_corpus_negative_labels':'unknown','ranges':{kind:{key:describe([r for r in result if r['kind']==kind],key) for key in ('top_cosine','max_top3_bge_logit')} for kind in kinds},'policy_modes':dict(collections.Counter(r['policy_mode'] for r in result)),'policy_frozen_before_gate':bool(args.gate),'automatic_false_identifier_count':sum(r.get('mandatory_identifier_present_in_search_index') is False for r in result),'scope':'Relevance/constraint diagnostics only; external topics are not validated corpus-wide no-answer labels','threshold_status':'Scores are not truth probabilities; negative labels remain unknown','calibration_limits':['20 positive RU cases from one family','No validated corpus-wide no-answer gold','Stress examples deliberately external, not all difficult normative negatives']}
    prefix='gate-' if args.gate else ''
    write_jsonl(out/(prefix+'diagnostics.jsonl'),result);write_json(out/(prefix+'summary.json'),summary);print(json.dumps(summary,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--variant',choices=['baseline','ordinary'],default='ordinary');p.add_argument('--gate',action='store_true');a=p.parse_args();run(a)
