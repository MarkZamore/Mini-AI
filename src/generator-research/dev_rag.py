"""Freeze a family-aware development subset and score saved full-application runs.

This helper never opens final data, infers semantic gold, or changes the corpus.
Only literal/text and citation/inventory diagnostics are automatic.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from common import DATA, OUT, ROOT, SEED, WORK, digest, gpu_lease, read_jsonl, write_json

DIRECTORY = OUT / 'dev-rag20'
EXPECTED_SELECTION_SHA = '31b09d6770ccfdfe169b2375beba218621f1a29e84d5890390eae9d99955b049'
MODULES = {'assistant', 'database', 'documents', 'graph', 'inference', 'search', 'sources'}
EXPECTED_WEIGHTS = {
    'base': '3acaea00a98af4e8cb7195259d7dd903b0cc749dff61a8cf47274c9a91ed02f6',
    'A': 'f31c4e102d1a691bc91e5e390c99ef3d07600ac948f3329b05a7569eaef8d369',
}
EXPECTED_WORKER = '60b57752154f6f1b8104c1d8880c9bc6551dda072db59b5dcce6ff6bd84e4ff8'
EXPECTED_ENCODER = 'e5545f739d90684e292ce8377a1d6d9abe5038ff3cf62c094e511133ccf09395'
EXPECTED_CORPUS = '58d6d60adbe58e4e9fe17ba0e843d40bc56906bd1dde9a7b2d12276401d7399a'


def package_snapshot(root, label):
    """Read-only preflight; real Java/native verifiers still run in the app."""
    root = root.resolve()
    model = root / 'model'
    manifest_path = model / 'worker.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
    if manifest['schemaVersion'] != 2 or manifest['contextTokens'] != 16384:
        raise ValueError('Unexpected candidate schema/context')
    if manifest['precision'] != 'Q6_K' or manifest['backend']['kind'] != 'cuda':
        raise ValueError('Unexpected candidate precision/backend')
    if (manifest['backend']['gpuMemoryBudgetBytes'] != 8 * 1024**3
            or manifest['backend']['reserveFreeGpuMemoryBytes'] != 3 * 1024**3
            or manifest['maxProcessMemoryBytes'] != 8 * 1024**3
            or manifest['minFreeMemoryBytes'] != 9 * 1024**3):
        raise ValueError('Unexpected resource budget')
    evidence = manifest['evidence']
    if evidence.get('reranker') or evidence.get('answerPolicy') or evidence.get('policy'):
        raise ValueError('Rejected BGE reranking/policy must remain disabled')
    if evidence['embedder']['sha256'] != EXPECTED_ENCODER:
        raise ValueError('Unexpected E5 model')
    if manifest['weights']['sha256'] != EXPECTED_WEIGHTS[label]:
        raise ValueError('Unexpected generator weights')
    files = {}
    for item in manifest['files']:
        name = item['path']
        path = (model / name).resolve()
        if not path.is_relative_to(model.resolve()) or name in files or not path.is_file():
            raise ValueError('Invalid package inventory entry: ' + name)
        actual = digest(path)
        if actual != item['sha256']:
            raise ValueError('Package hash mismatch: ' + name)
        files[name] = {'sha256': actual, 'bytes': path.stat().st_size}
    if files[manifest['executable']]['sha256'] != EXPECTED_WORKER:
        raise ValueError('Unexpected native worker')
    corpus = root / 'db/enterprise-ai.db'
    # The current SQLite filename is fixed by DatabaseModule, not this runner.
    if not corpus.is_file():
        corpus = root / 'db/enterpriseai.sqlite'
    if not corpus.is_file():
        candidates = list((root / 'db').glob('*.sqlite')) + list((root / 'db').glob('*.db'))
        if len(candidates) != 1:
            raise ValueError('Cannot identify a unique canonical database')
        corpus = candidates[0]
    if digest(corpus) != EXPECTED_CORPUS:
        raise ValueError('Canonical corpus fingerprint differs')
    native = {p.relative_to(root / 'native').as_posix(): digest(p)
              for p in sorted((root / 'native').rglob('*')) if p.is_file()}
    if not native:
        raise ValueError('Real WindowsPlatform native libraries are absent')
    return {'root': str(root), 'manifest_sha256': digest(manifest_path), 'manifest': manifest,
            'files': files, 'native': native, 'corpus_path': str(corpus), 'corpus_sha256': EXPECTED_CORPUS}


def classpath_snapshot(classpath):
    result = []
    for entry in classpath.split(os.pathsep):
        path = Path(entry).resolve()
        if path.name == '*':
            paths = sorted(path.parent.glob('*.jar'))
        elif path.is_dir():
            paths = sorted(p for p in path.rglob('*') if p.is_file())
        else:
            paths = [path]
        if not paths:
            raise ValueError('Empty classpath entry: ' + entry)
        result.extend({'path': str(p), 'sha256': digest(p), 'bytes': p.stat().st_size} for p in paths)
    return result


def run_pair(args):
    """Call only after root confirms fresh Java and both runtime roots ready."""
    manifest, _ = frozen_inputs()
    destination = DIRECTORY / args.label
    if destination.exists() or not re.fullmatch(r'[A-Za-z0-9_-]+', args.label):
        raise ValueError('Use a new, simple run label; prior results are immutable')
    if shutil.disk_usage(ROOT.anchor).free < 20 * 1024**3:
        raise ValueError('Preserve at least 20 GiB free on the system volume')
    snapshots = {label: package_snapshot(path, label)
                 for label, path in [('base', args.base_root), ('A', args.a_root)]}
    a_manifest = json.loads(json.dumps(snapshots['A']['manifest']))
    base_manifest = snapshots['base']['manifest']
    # Generation weights are the only accepted package difference. Licenses,
    # tokenizer, vectors, limits, CPU evidence and backend must be identical.
    a_weights_path = a_manifest['weights']['path']
    a_manifest['weights'] = dict(base_manifest['weights'])
    for item in a_manifest['files']:
        if item['path'] == a_weights_path:
            item.update(base_manifest['weights'])
    if a_manifest != base_manifest or snapshots['base']['native'] != snapshots['A']['native']:
        raise ValueError('Candidates differ beyond generation weights')
    destination.mkdir()
    snapshot = {'schemaVersion': 1, 'input_sha256': manifest['input_sha256'],
                'runner_sha256': digest(Path(__file__)), 'java': str(args.java.resolve()),
                'java_sha256': digest(args.java), 'classpath': args.classpath,
                'classpath_files': classpath_snapshot(args.classpath), 'jvm_options': args.jvm_option,
                'packages': snapshots, 'final_read': False, 'human_verified': False,
                'order': ['base', 'A'], 'state': 'preflight-complete'}
    write_json(destination / 'run.json', snapshot)
    with gpu_lease('generator_rag/dev-rag20/' + args.label):
        for label in ('base', 'A'):
            if shutil.disk_usage(ROOT.anchor).free < 20 * 1024**3:
                raise ValueError('System-volume reserve fell below 20 GiB')
            output = destination / (label + '-results.jsonl')
            command = [str(args.java), *args.jvm_option, '-cp', args.classpath,
                       'ai.enterprise.research.RagEvaluation', snapshots[label]['root'],
                       str(DIRECTORY / 'input.jsonl'), str(output), 'answer', '20']
            record = {'label': label, 'command': command, 'state': 'starting'}
            write_json(destination / (label + '-run.json'), record)
            print(json.dumps({'candidate': label, 'state': 'starting', 'output': str(output)}), flush=True)
            process = None
            with (destination / (label + '-console.log')).open('xb') as console:
                try:
                    process = subprocess.Popen(command, cwd=ROOT, stdout=console, stderr=subprocess.STDOUT)
                    record.update(pid=process.pid, state='running')
                    write_json(destination / (label + '-run.json'), record)
                    result = process.wait()
                    record.update(state='finished', exit_code=result)
                    write_json(destination / (label + '-run.json'), record)
                    print(json.dumps({'candidate': label, 'state': 'finished', 'exit_code': result}), flush=True)
                    if result:
                        raise RuntimeError('Real full-application candidate failed: ' + label)
                finally:
                    if process is not None and process.poll() is None:
                        process.terminate()
                        process.wait()
            if digest(Path(snapshots[label]['root']) / 'model/worker.json') != snapshots[label]['manifest_sha256']:
                raise ValueError('Candidate manifest changed during evaluation')
    snapshot['state'] = 'both-runs-finished'
    write_json(destination / 'run.json', snapshot)
    score([f'{label}={destination / (label + "-results.jsonl")}' for label in ('base', 'A')],
          destination / 'scores.json')


def normalize(text):
    return ' '.join(re.findall(r'\w+', text.casefold()))


def content_hash(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def prepare():
    source = DATA / 'selection.jsonl'
    if digest(source) != EXPECTED_SELECTION_SHA:
        raise ValueError('Frozen selection SHA differs')
    rows = read_jsonl(source)
    if len(rows) != 80 or any(r['split'] != 'selection' or len(r['family_ids']) != 1 for r in rows):
        raise ValueError('Unexpected selection schema or counts')
    counts = Counter(r['family_ids'][0] for r in rows)
    dominant = counts.most_common(1)[0][0]
    minority = [r for r in rows if r['family_ids'][0] != dominant]
    if len(minority) != 8 or counts[dominant] != 72:
        raise ValueError('Expected all eight minority-family cases and dominant family of 72')
    chosen = list(minority)
    # Hash ordering makes the seed selection independent of input line order
    # and Python's PRNG implementation/version. Every ID has a fixed priority.
    priority = lambda label, row: hashlib.sha256(f'{SEED}:dev-rag20:{label}:{row["id"]}'.encode()).hexdigest()
    for language in ('ru', 'kz'):
        pool = [r for r in rows if r['family_ids'][0] == dominant and r['language'] == language]
        if len(pool) != 36:
            raise ValueError('Expected 36 dominant-family cases per language')
        chosen.extend(sorted(pool, key=lambda r: (priority(language, r), r['id']))[:6])
    chosen.sort(key=lambda r: (priority('order', r), r['id']))
    if len(chosen) != 20 or len({r['id'] for r in chosen}) != 20:
        raise ValueError('Expected twenty unique cases')
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    target = DIRECTORY / 'input.jsonl'
    raw = ''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in chosen).encode('utf-8')
    manifest = {
        'schemaVersion': 1, 'subset': 'dev-rag20', 'seed': SEED,
        'selection_sha256': EXPECTED_SELECTION_SHA,
        'input_sha256': hashlib.sha256(raw).hexdigest(), 'input': str(target),
        'selection_method': 'All eight non-dominant-family cases; six RU and six KZ dominant cases ranked by SHA256(seed:dev-rag20:language:id); final order by SHA256(seed:dev-rag20:order:id)',
        'cases': len(chosen), 'dominant_family_id': dominant,
        'family_counts': dict(Counter(r['family_ids'][0] for r in chosen)),
        'language_counts': dict(Counter(r['language'] for r in chosen)),
        'dominant_language_counts': dict(Counter(r['language'] for r in chosen if r['family_ids'][0] == dominant)),
        'minority_cases': len(minority), 'intent_groups': len({r['intent_group'] for r in chosen}),
        'ids_in_order': [r['id'] for r in chosen],
        'frozen_before_answers': True, 'human_verified': False,
        'semantic_completeness': 'unknown', 'final_read': False,
        'comparison': {'contextTokens': 16384, 'precision': 'Q6_K', 'sampler': 'greedy',
                       'evidence_policy': 'same ordinary E5 index + reciprocal-rank fusion; BGE reranking and BGE answer gate disabled after development rejection',
                       'contexts': 'Canonical full-application retrieval; require exact per-case ordered source equality across candidates'},
        'limitations': ['Document/anchor-assisted generated questions, not ordinary-question gold',
                       'All minority cases are RU, so total language counts are RU14/KZ6',
                       'Same source families as selection; not a blind or final estimate',
                       'Literal atom coverage and citation validity are not semantic completeness'],
    }
    if target.exists():
        if target.read_bytes() != raw:
            raise FileExistsError('Refusing to replace frozen input')
        previous = json.loads((DIRECTORY / 'manifest.json').read_text(encoding='utf-8'))
        if previous != manifest:
            raise ValueError('Frozen manifest differs')
    else:
        with target.open('xb') as stream:
            stream.write(raw)
        write_json(DIRECTORY / 'manifest.json', manifest)
        (DIRECTORY / 'input.sha256').write_text(manifest['input_sha256'] + '  input.jsonl\n', encoding='ascii')
    print(json.dumps({'input': str(target), 'sha256': manifest['input_sha256'],
                      'family_counts': manifest['family_counts'], 'language_counts': manifest['language_counts']}, ensure_ascii=False))


def frozen_inputs():
    manifest = json.loads((DIRECTORY / 'manifest.json').read_text(encoding='utf-8'))
    path = DIRECTORY / 'input.jsonl'
    if digest(path) != manifest['input_sha256']:
        raise ValueError('Frozen input SHA changed')
    rows = read_jsonl(path)
    if len(rows) != 20 or [r['id'] for r in rows] != manifest['ids_in_order']:
        raise ValueError('Frozen input inventory differs')
    return manifest, {r['id']: r for r in rows}


def source_inventory(row):
    messages = [m for m in row.get('history', []) if m.get('role') == 'assistant']
    return messages[-1].get('sources', []) if messages else []


def case_score(row, gold):
    messages = [m for m in row.get('history', []) if m.get('role') == 'assistant']
    response = messages[-1] if messages else {}
    answer = response.get('text', '')
    sources = source_inventory(row)
    ids = [str(s.get('fragment_id', '')) for s in sources]
    if len(ids) != len(set(ids)) or any(not i for i in ids):
        raise ValueError('Malformed/duplicate canonical source inventory')
    citations = re.findall(r'\[source:([^\]\r\n]+)\]', answer)
    unknown = [c for c in citations if c not in ids]
    required = gold['required_atoms']
    present = [normalize(a['text']) in normalize(answer) for a in required]
    # Session done text includes sources warnings as user-visible text. No
    # inferred semantic label is produced even when all required spans occur.
    found = row.get('result', {})
    errors = [e.get('error') for e in row.get('events', []) if e.get('error')]
    if found.get('error'):
        errors.append(found['error'])
    error_codes = sorted({str(e.get('code', 'UNKNOWN')) for e in errors if isinstance(e, dict)})
    positives = set(gold['positive_ids'])
    states = [e for e in row.get('events', []) if e.get('type') == 'state']
    generation_started = next((e.get('atSeconds') for e in states
        if str(e.get('text', '')).startswith('Подготовка модели и ожидание ответа')), None)
    first_model_text = next((e.get('atSeconds') for e in states
        if str(e.get('text', '')).startswith('Формирование ответа')), None)
    result = {
        'id': row['id'], 'language': gold['language'], 'family_ids': gold['family_ids'],
        'success': found.get('success') is True and not errors,
        'assistant_state': response.get('state'), 'error_codes': error_codes,
        'answer': answer, 'answer_chars': len(answer),
        'literal_atoms_present': sum(present), 'literal_atoms_total': len(present),
        'literal_atom_coverage': sum(present) / len(present) if present else None,
        'all_literal_atoms_present': all(present) if present else None,
        'citations': citations, 'unknown_citations': unknown,
        'current_source_citation_present': any(c in ids for c in citations),
        'positive_citation_present': bool(set(citations) & positives),
        'positive_source_retrieved': bool(set(ids) & positives),
        'retrieved_source_ids': ids, 'ordered_source_sha256': content_hash(sources),
        'retrieved_evidence_sha256': content_hash([{k: s.get(k) for k in
            ('fragment_id', 'document_id', 'title', 'text', 'source_refs', 'source_sha256', 'base_id', 'content_version')}
            for s in sources]),
        'first_visible_seconds': row.get('firstNonemptyTextSeconds'), 'full_seconds': row.get('fullSeconds'),
        'generation_started_seconds': generation_started,
        'generation_attempted': generation_started is not None,
        'pre_generation_refusal_or_clarification': found.get('success') is True
            and generation_started is None
            and any(str(e.get('text', '')).startswith('Проверка достаточности источников') for e in states),
        'first_model_text_seconds_from_send': first_model_text,
        'model_ttft_seconds': first_model_text - generation_started
            if first_model_text is not None and generation_started is not None else None,
        'model_ttft_method': 'Session first raw nonempty model chunk state minus generation-preparation state; includes worker startup if cold',
        'event_states': [e.get('text', '') for e in states],
        'semantic_correctness': 'unknown', 'semantic_completeness': 'unknown',
        'human_verified': False,
    }
    modules = row.get('modules', [])
    result['full_composition'] = len(modules) == 7 and set(modules) == MODULES
    if row.get('question') != gold['question']:
        raise ValueError('Question differs from frozen input')
    return result


def score(paths, output):
    manifest, inputs = frozen_inputs()
    variants = {}
    for item in paths:
        if '=' not in item:
            raise ValueError('Use --result label=path.jsonl')
        label, filename = item.split('=', 1)
        if label in variants:
            raise ValueError('Duplicate candidate label')
        path = Path(filename).resolve()
        rows = read_jsonl(path)
        if len(rows) != 20 or [r['id'] for r in rows] != manifest['ids_in_order']:
            raise ValueError('Incomplete, reordered, duplicate or foreign result inventory')
        cases = [case_score(row, inputs[row['id']]) for row in rows]
        variants[label] = {
            'path': str(path), 'sha256': digest(path), 'cases': cases,
            'summary': {
                'cases': 20, 'successful_cases': sum(c['success'] for c in cases),
                'full_composition_cases': sum(c['full_composition'] for c in cases),
                'generation_attempted_cases': sum(c['generation_attempted'] for c in cases),
                'pre_generation_refusal_or_clarification_cases': sum(c['pre_generation_refusal_or_clarification'] for c in cases),
                'error_codes': dict(Counter(code for c in cases for code in c['error_codes'])),
                'positive_source_retrieved_cases': sum(c['positive_source_retrieved'] for c in cases),
                'current_source_citation_cases': sum(c['current_source_citation_present'] for c in cases),
                'positive_citation_cases': sum(c['positive_citation_present'] for c in cases),
                'unknown_citation_count': sum(len(c['unknown_citations']) for c in cases),
                'literal_atoms_present': sum(c['literal_atoms_present'] for c in cases),
                'literal_atoms_total': sum(c['literal_atoms_total'] for c in cases),
                'macro_literal_atom_coverage': sum(c['literal_atom_coverage'] for c in cases) / 20,
                'semantic_correctness': 'unknown', 'semantic_completeness': 'unknown',
            },
        }
    contexts = []
    labels = list(variants)
    for index, case_id in enumerate(manifest['ids_in_order']):
        values = [variants[label]['cases'][index] for label in labels]
        contexts.append({'id': case_id,
            'ordered_sources_identical': len({c['ordered_source_sha256'] for c in values}) == 1,
            'ordered_evidence_identical': len({c['retrieved_evidence_sha256'] for c in values}) == 1})
    report = {'subset': 'dev-rag20', 'input_sha256': manifest['input_sha256'],
        'human_verified': False, 'blind': False, 'final_read': False,
        'semantic_quality_acceptance': False, 'variants': variants, 'paired_contexts': contexts,
        'identical_paired_context_cases': sum(c['ordered_sources_identical'] for c in contexts),
        'method': 'Literal span and canonical citation inventory checks; no semantic inference',
        'latency_note': 'Full response latency is affected by answer length, gate/refusal, context and startup; not a compute speedup measure'}
    if output.exists():
        raise FileExistsError('Preserve previous diagnostic score report')
    write_json(output, report)
    print(json.dumps({'output': str(output), 'summaries': {k: v['summary'] for k, v in variants.items()},
                      'identical_paired_context_cases': report['identical_paired_context_cases']}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    command = parser.add_subparsers(dest='command', required=True)
    command.add_parser('prepare')
    scorer = command.add_parser('score')
    scorer.add_argument('--result', action='append', required=True)
    scorer.add_argument('--output', type=Path, required=True)
    runner = command.add_parser('run', help='Only after root confirms complete fresh Java/runtime readiness')
    runner.add_argument('--label', required=True)
    runner.add_argument('--java', type=Path, required=True)
    runner.add_argument('--classpath', required=True)
    runner.add_argument('--base-root', type=Path, required=True)
    runner.add_argument('--a-root', type=Path, required=True)
    runner.add_argument('--jvm-option', action='append', default=[])
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare()
    elif args.command == 'score':
        score(args.result, args.output)
    else:
        run_pair(args)


if __name__ == '__main__':
    main()
