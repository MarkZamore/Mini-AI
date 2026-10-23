"""Uncertainty for context-coverage differences; never answer-quality scores."""
from __future__ import annotations
import argparse
from collections import defaultdict
import json
from pathlib import Path
import random
import statistics
from common import SEED, digest, read_jsonl, write_json
from dev_rag_statistics import quantile


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('directory', type=Path); args = parser.parse_args()
    target = args.directory / 'paired-intervals.json'
    if target.exists(): raise FileExistsError('Preserve previous intervals')
    source = args.directory / 'analysis.json'
    data = json.loads(source.read_text(encoding='utf-8'))
    run = json.loads((args.directory / 'run.json').read_text(encoding='utf-8'))
    gold = {r['id']: r for r in read_jsonl(run['input_path'])}
    cases = [c for c in data['cases'] if c['valid_control'] and c['variants']['with_neighbors10']['mandatory_id_coverage'] is not None]
    parent = {c['id']: c['id'] for c in cases}
    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    def join(x,y): parent[find(x)] = find(y)
    seen = {}
    # Multi-source families and repeated intentions must not fall into
    # independent clusters. Connected components preserve that dependence.
    for c in cases:
        markers = ['family:' + str(f) for f in gold[c['id']].get('family_ids', [])]
        if gold[c['id']].get('intent_group'): markers.append('intent:' + str(gold[c['id']]['intent_group']))
        for marker in markers:
            if marker in seen: join(c['id'], seen[marker])
            else: seen[marker] = c['id']
    clusters = defaultdict(list)
    for c in cases: clusters[find(c['id'])].append(c)
    groups = sorted(clusters)
    output = {'input_analysis_sha256': digest(source), 'scope': 'Paired evidence-context ID coverage, not answer quality',
              'final_read': False, 'human_verified': False, 'cases_with_known_mandatory_ids': len(cases),
              'clusters': len(groups), 'cluster_sizes': sorted([len(g) for g in clusters.values()], reverse=True),
              'unknown_or_invalid_cases_not_scored_as_success': len(data['cases']) - len(cases),
              'method': 'Paired percentile bootstrap over connected document-family/intention components; no independence claimed for linked cases',
              'seed': SEED, 'replicates': 20000, 'metrics': {}}
    for field in ('mandatory_id_coverage', 'all_known_mandatory_ids_present'):
        deltas = {c['id']: float(c['variants']['with_neighbors10'][field]) - float(c['variants']['ranked_anchors10'][field]) for c in cases}
        samples=[]; rng=random.Random(SEED)
        if groups:
            for _ in range(20000):
                values=[deltas[c['id']] for group in rng.choices(groups,k=len(groups)) for c in clusters[group]]
                samples.append(statistics.mean(values))
            interval=[quantile(samples,.025),quantile(samples,.975)]
            output['metrics'][field]={'difference':statistics.mean(deltas.values()),'ci95':interval,'includes_zero':interval[0]<=0<=interval[1]}
        else: output['metrics'][field]={'difference':None,'ci95':None,'status':'unknown'}
    output['limitations']=['Intervals are conditional on the existing automatically identified mandatory spans/IDs.',
                           'A zero difference does not prove neighbors are useless for unlabelled conditions, exceptions, or generator output.',
                           'Very few connected families/intention components make broad transfer conclusions weak.']
    write_json(target,output)
    print(json.dumps({'output':str(target),'clusters':len(groups),'metrics':output['metrics']},ensure_ascii=False))


if __name__ == '__main__': main()
