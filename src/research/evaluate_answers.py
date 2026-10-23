"""Conservative source/rubric evaluation; never pretends to be a human judge.

Run with --split selection during development. Final requires a complete freeze
manifest; final labels and questions are only opened by this separate evaluator.
Unknown semantic judgements count as non-success in conservative rates.
"""
import argparse,collections,json,pathlib,re
from data_pipeline import DEFAULT_OUT,ROOT,digest,hid,norm,read_jsonl,write_json,write_jsonl

MARKER=re.compile(r'\[source:([^\]\s]+)\]')
def evaluate_one(example,response,sources):
    text=response.get('text','');cites=MARKER.findall(text)
    supplied=response.get('context_ids')
    context_known=isinstance(supplied,list)
    supplied=supplied if context_known else []
    alias=response.get('citation_aliases',{})
    resolved=[alias.get(c,c) for c in cites]
    # The verified source subset is smaller than the actual corpus. A cited ID
    # outside this subset is unknown, not proof that the corpus ID is invalid.
    invalid=bool(context_known and any(c not in supplied for c in resolved))
    citation_unknown=not context_known or any(c not in sources for c in resolved)
    valid=False if invalid else (None if citation_unknown else True)
    plain=norm(MARKER.sub('',text)).casefold()
    atom_results=[]
    for atom in example['required_atoms']:
        exact=norm(atom['text']).casefold() in plain
        atom_results.append({'fragment_id':atom['fragment_id'],'literal_atom_present':exact,'citation_present':atom['fragment_id'] in resolved,'semantic_paraphrase_assessment':'not_needed_for_exact_match' if exact else 'unknown'})
    complete=all(a['literal_atom_present'] and a['citation_present'] for a in atom_results)
    # Extra paraphrases cannot be verified by a string metric. Classify them as
    # unknown rather than silently accepting confident unsupported assertions.
    residue=plain
    for atom in example['required_atoms']:residue=residue.replace(norm(atom['text']).casefold(),'')
    residue=re.sub(r'краткий ответ|основание|условия или порядок действий|что делать дальше','',residue)
    residue=re.sub(r'[\W_]+','',residue)
    additional_assertions='unknown' if residue else 'none_detected_by_literal_rubric'
    rubric_success=bool(text and valid is True and complete and additional_assertions!='unknown')
    critical='mechanically_invalid_citation' if invalid else 'unknown_semantic_critical_error_status'
    return {'id':example['id'],'language':example['language'],'families':example['family_ids'],'thematic_transfer':example.get('thematic_transfer',False),'text_present':bool(text),'actual_context_ids_provided':context_known,'citation_ids_valid':valid,'citation_validation_unknown':bool(citation_unknown and not invalid),'atom_results':atom_results,'literal_rubric_complete':complete,'additional_assertions':additional_assertions,'automatic_literal_rubric_success':rubric_success,'independent_semantic_success':'unknown','critical_error_status':critical,'human_review':False,'ttft_seconds':response.get('ttft_seconds'),'full_response_seconds':response.get('full_response_seconds')}

def main():
    p=argparse.ArgumentParser();p.add_argument('--split',choices=['selection','calibration','gatecheck','final'],default='selection');p.add_argument('--answers',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--freeze',type=pathlib.Path);a=p.parse_args()
    data=DEFAULT_OUT
    if a.split=='final':
        if not a.freeze:raise ValueError('Final requires complete model/prompt/retrieval/threshold freeze')
        freeze=json.loads(a.freeze.read_text(encoding='utf-8'))
        for key in ('e5_variant','generator_sha256','prompt_sha256','corpus_sha256','thresholds','final_set_sha256'):assert key in freeze,key
        assert freeze['final_set_sha256']==digest(data/'sealed-final/final.jsonl')
        path=data/'sealed-final/final.jsonl'
        with (data/'disclosure-log.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps({'event':'final_opened_for_frozen_evaluation','freeze_sha256':digest(a.freeze),'answers_sha256':digest(a.answers),'date':'2026-10-24','must_not_be_used_for_further_tuning':True})+'\n')
    else:path=data/(a.split+'.jsonl')
    examples=read_jsonl(path);response_rows=read_jsonl(a.answers);responses={r['id']:r for r in response_rows};sources={s['fragment_id']:s for s in read_jsonl(data/'sources.jsonl')}
    assert len(responses)==len(response_rows),'Repeated response IDs would silently overwrite an answer'
    assert not set(responses)-{e['id'] for e in examples},'Unknown response IDs'
    result=[evaluate_one(e,responses.get(e['id'],{}),sources) for e in examples]
    n=len(result);success=sum(r['automatic_literal_rubric_success'] for r in result)
    summary={'split':a.split,'questions':n,'responses':sum(r['text_present'] for r in result),'automatic_literal_rubric_success_count':success,'automatic_literal_rubric_success_rate':success/n if n else 0.,'known_independent_semantic_judgements':0,'unknown_independent_semantic_judgements':n,'known_independent_semantic_coverage':0.,'unknown_independent_semantic_coverage':1. if n else 0.,'independent_semantic_success_conservative_lower_bound':0.,'invalid_citation_answers':sum(r['citation_ids_valid'] is False for r in result),'unknown_citation_validation_answers':sum(r['citation_ids_valid'] is None for r in result),'unknown_semantic_error_answers':sum(r['critical_error_status']=='unknown_semantic_critical_error_status' for r in result),'human_review':False,'limitations':['Literal source/rubric proxy; not an independent semantic assessment','Unknowns remain in denominators and count as non-success','Citation outside the verified native subset is unknown, not proof of invalid corpus ID','Actual context_ids required; no gold context substitution','Absence of invalid citations does not prove factual correctness','Semantic completeness and critical errors remain unverified without independent assessment'],'answers_sha256':digest(a.answers),'questions_sha256':digest(path)}
    a.out.mkdir(parents=True,exist_ok=True);write_jsonl(a.out/'answer-results.jsonl',result);write_json(a.out/'answer-metrics.json',summary);print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
