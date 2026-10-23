"""Paired source/citation diagnostics and family/intention cluster intervals.

Never infer a semantic answer-quality label from literal matches. The dev20
subset is selected from existing development cases, not an independent test.
"""
from __future__ import annotations
import argparse
from collections import defaultdict
import json
from pathlib import Path
import random
import statistics
from common import SEED, digest, write_json


def quantile(values, fraction):
    values = sorted(values)
    if not values:
        return None
    location = (len(values) - 1) * fraction
    low = int(location)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (location - low)


def paired_interval(base, alternative, field, replicates=20000):
    clusters = defaultdict(list)
    for x, y in zip(base, alternative, strict=True):
        if x['id'] != y['id'] or x['family_ids'] != y['family_ids'] or len(x['family_ids']) != 1:
            raise ValueError('Paired family inventory differs')
        # All twenty dev20 cases have distinct intention groups. Resampling
        # cases within family is therefore intention-cluster resampling here.
        clusters[x['family_ids'][0]].append(float(y[field]) - float(x[field]))
    families = sorted(clusters)
    rng = random.Random(SEED)
    samples = []
    for _ in range(replicates):
        values = []
        for family in rng.choices(families, k=len(families)):
            group = clusters[family]
            values.extend(rng.choices(group, k=len(group)))
        samples.append(statistics.mean(values))
    lower, upper = quantile(samples, .025), quantile(samples, .975)
    return {'difference': statistics.mean(float(y[field]) - float(x[field]) for x, y in zip(base, alternative, strict=True)),
            'ci95': [lower, upper], 'includes_zero': lower <= 0 <= upper,
            'seed': SEED, 'replicates': replicates, 'families': len(families),
            'family_sizes': sorted((len(v) for v in clusters.values()), reverse=True),
            'method': 'Paired hierarchical percentile bootstrap: document family, then distinct case/intention within sampled family',
            'limitation': 'Five dependent document families with strong imbalance; diagnostic interval, not semantic-quality proof'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('scores', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError('Preserve prior statistics')
    data = json.loads(args.scores.read_text(encoding='utf-8'))
    if data['identical_paired_context_cases'] != 20 or set(data['variants']) != {'base', 'A'}:
        raise ValueError('Require all twenty paired contexts and exactly base/A')
    stats = {'source': str(args.scores.resolve()), 'source_sha256': digest(args.scores),
             'final_read': False, 'human_verified': False, 'semantic_quality': 'unknown',
             'time_is_inference_only': True, 'latencies': {}, 'diagnostic_paired_differences': {}}
    for label, value in data['variants'].items():
        cases = value['cases']
        fields = {}
        for field in ('first_visible_seconds', 'full_seconds', 'model_ttft_seconds', 'answer_chars'):
            items = [c[field] for c in cases if c[field] is not None]
            warm = [c[field] for c in cases[1:] if c[field] is not None]
            fields[field] = {'cases': len(items), 'median': statistics.median(items) if items else None,
                             'p95_linear': quantile(items, .95), 'first_case': cases[0][field],
                             'warm_cases': len(warm), 'warm_median': statistics.median(warm) if warm else None,
                             'warm_p95_linear': quantile(warm, .95)}
        stats['latencies'][label] = fields
    for field in ('literal_atom_coverage', 'positive_citation_present', 'current_source_citation_present'):
        stats['diagnostic_paired_differences'][field] = paired_interval(
            data['variants']['base']['cases'], data['variants']['A']['cases'], field)
    stats['interpretation'] = [
        'Literal coverage is not semantic correctness: a negative or contradictory answer can contain the target words.',
        'Citation existence does not establish support for an assertion.',
        'Response lengths differ. Full-answer latency differences cannot be attributed to compute throughput.',
        'answer_chars counts canonical saved history, including source-quality warnings; the UI may present these more compactly.',
        'First case includes cold worker initialization; subsequent cases reuse the worker and OS cache.',
        'Teams was stopped. These timings do not prove performance during a Teams meeting.',
    ]
    write_json(args.output, stats)
    print(json.dumps({'output': str(args.output), 'differences': stats['diagnostic_paired_differences']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
