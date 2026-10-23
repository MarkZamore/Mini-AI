"""Structural data audit, not a semantic/human evaluation."""
import collections,json
from data_pipeline import DEFAULT_OUT,DEMO,hid,read_jsonl,write_json

def main():
    data=DEFAULT_OUT;sources={s['fragment_id']:s for s in read_jsonl(data/'sources.jsonl')}
    sets={k:read_jsonl(data/(k+'.jsonl')) for k in ('train','selection','calibration','gatecheck')}
    families={k:{f for e in rows for f in e['family_ids']} for k,rows in sets.items()}
    allids=set()
    for k,rows in sets.items():
        for e in rows:
            assert e['id'] not in allids;allids.add(e['id'])
            assert not DEMO.search(e['question'])
            for a in e['required_atoms']:
                assert a['text'] in sources[a['fragment_id']]['text']
                assert sources[a['fragment_id']]['human_verified'] is False
    for a in families:
        for b in families:
            if a!=b:assert not families[a]&families[b],(a,b)
    assignment=json.loads((data/'family-assignment.json').read_text())
    assert not families['train']&{f for f,s in assignment.items() if s=='final'}
    # Source text is public to the local RAG index. This source-only audit does
    # not open sealed-final questions/labels or use model scores.
    import re
    signatures={sid:set(' '.join(w[i:i+5]) for i in range(len(w)-4)) for sid,s in sources.items() if len(w:=re.findall(r'\w+',s['text'].casefold()))>=16}
    source_list=sorted(signatures);near=[]
    for i,a in enumerate(source_list):
        for b in source_list[:i]:
            if sources[a]['family_id']==sources[b]['family_id']:continue
            sa=assignment.get(sources[a]['family_id']);sb=assignment.get(sources[b]['family_id'])
            if not sa or not sb or sa==sb:continue
            score=len(signatures[a]&signatures[b])/min(len(signatures[a]),len(signatures[b]))
            if score>=.9:near.append({'a':a,'b':b,'split_a':sa,'split_b':sb,'five_word_containment':score})
    result={'structural_checks_passed':True,'family_disjoint':True,'atom_literal_substring_checks_passed':True,'demo_question_filter_passed':True,'final_questions_read':False,'near_duplicate_cross_split_sources':near,'near_duplicate_count':len(near),'human_review':False,'semantic_gold_acceptance':False}
    write_json(data/'data-integrity.json',result);print(json.dumps({k:v for k,v in result.items() if k!='near_duplicate_cross_split_sources'},indent=2))
if __name__=='__main__':main()
