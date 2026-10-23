"""Full-app evidence-context control, never generator/answer-quality evaluation."""
from __future__ import annotations
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
from common import ROOT, WORK, digest, gpu_lease, read_jsonl, write_json
from dev_rag import classpath_snapshot

AREA = WORK / 'neighbor-context'
SEARCH = ROOT / 'src/modules/search/service/ai/enterprise/search'
RULE_FILES = ('AssistantSearch.java', 'QuestionQuery.java', 'QueryNormalizer.java', 'RussianStemmer.java', 'CurrentCorpusConnector.java')
MODULES = {'assistant', 'database', 'documents', 'graph', 'inference', 'search', 'sources'}


def constant(source, name):
    expression = re.search(r'\b' + name + r'\s*=\s*stems\((.*?)\);', source, re.S)
    if not expression:
        raise ValueError('Cannot read source constant ' + name)
    strings = re.findall(r'"(?:[^"\\]|\\.)*"', expression[1])
    if not strings:
        raise ValueError('Empty source constant')
    return ''.join(json.loads(s) for s in strings)


def prepare():
    directory = AREA / 'rules'
    if directory.exists():
        previous = json.loads((directory / 'rules.json').read_text(encoding='utf-8'))
        if any(digest(SEARCH / name) != sha for name, sha in previous['source_sha256'].items()):
            raise ValueError('Search source changed after rules freeze; preserve previous rules and use a new experiment')
        print(json.dumps({'rules': str(directory / 'rules.json'), 'existing_frozen_rules': True}))
        return
    sources = {name: (SEARCH / name).read_text(encoding='utf-8') for name in RULE_FILES}
    find = sources['AssistantSearch.java']
    obligations = ('int maximum=Math.min(100,Math.max(1,limit));',
                   'lexical(query,100,documentIds,sections,token)',
                   'int anchors=expand?Math.max(1,(maximum+1)/2):maximum;',
                   'if(selected.size()==anchors) break;',
                   'if(expand) {', 'List<Api.SearchHit> seeds=List.copyOf(selected.values());')
    if not all(s in find for s in obligations):
        raise ValueError('Production limit/anchor logic changed; this control needs fresh proof')
    task = re.search(r'static Set<String> taskWords\(String query\)\s*\{(.*?)\n    \}', sources['QuestionQuery.java'], re.S)
    if not task or not all(s in task[1] for s in ('new HashSet<>(stems(query))', 'task.removeAll(FILLER)', 'task.removeAll(OUTPUT)')):
        raise ValueError('QuestionQuery task-word logic differs')
    rules = {'schemaVersion': 1, 'CONDITIONS': constant(find, 'CONDITIONS'), 'PROCEDURES': constant(find, 'PROCEDURES'),
             'FILLER': constant(sources['QuestionQuery.java'], 'FILLER'), 'OUTPUT': constant(sources['QuestionQuery.java'], 'OUTPUT'),
             'source_sha256': {name: digest(SEARCH / name) for name in RULE_FILES},
             'classification': 'Pure source-verified reproduction using the original public QueryNormalizer; no reflection/private services/context mutation',
             'predeclared_validity': 'For expanded cases require >=10 eligible unique dense candidates, identical first five seeds, and extra context IDs proven adjacent to those seeds; otherwise mark control unproven.',
             'scope': 'Known evidence IDs/atoms in context only; no generation or answer-quality claim', 'final_read': False}
    directory.mkdir(parents=True)
    for name in RULE_FILES:
        shutil.copy2(SEARCH / name, directory / name)
    write_json(directory / 'rules.json', rules)
    (directory / 'proof.md').write_text(
        '# Почему это контроль соседнего контекста\n\n'
        '`AssistantSearch.find` сначала строит `ranked` через lexical(query,100)+hybrid, без использования внешнего limit. '
        'Без BGE это тот же детерминированный RRF. Затем limit10 означает maximum10; при expand=true anchors5, '
        'при limit20 anchors10. Все anchors вставляются в LinkedHashMap ДО чтения соседей. Поэтому первые10 результата20 '
        'являются десятью исходными ranked anchors, **если ranked содержит хотя бы10кандидатов**. '
        'В control эта предпосылка проверяется нижней оценкой числа допустимых dense candidates через публичные API.\n\n'
        'Limit10 при expand=true содержит те же первые5anchors, затем соседей в том же документе и при необходимости '
        'добавления из ranked. Сравнение двух списков одинаковой длины10 проверяет замену части нижних anchors соседними '
        'фрагментами. Это не сравнение увеличенного генераторного контекста и не изменение lexical/semantic ранжирования.\n\n'
        'При expand=false anchors=maximum, соседняя ветка не исполняется. Поэтому limit10 и первые10 limit20 '
        'должны совпасть, включая случаи с коротким ranked. Это проверяемый отрицательный контроль.\n\n'
        'expand воспроизводится тем же publicQueryNormalizer со строками CONDITIONS/PROCEDURES/FILLER/OUTPUT '
        'из сохранённых исходников. Это обычная чистая функция, без private access и замены сервисов Context. '
        'По каждому случаю сохраняются taskwords, реальные IDs и исходные результаты двух search calls.\n\n'
        'ПолныйGeneratedCatalog(7модулей), настоящийWindowsPlatform и штатныйCPU E5 worker сохраняются. '
        'Генератор не вызывается. Совпадение ID/текста доказывает только покрытие известных оснований в контексте, '
        'а не семантическую полноту нормативного ответа.\n', encoding='utf-8')
    print(json.dumps({'rules': str(directory / 'rules.json'), 'source_hashes': rules['source_sha256']}, ensure_ascii=False))


def run(args):
    prepare()
    directory = AREA / args.label
    if directory.exists() or not re.fullmatch(r'[A-Za-z0-9_-]+', args.label):
        raise FileExistsError('Choose a new simple run label')
    inputs = read_jsonl(args.input)
    if not inputs or any(not r.get('id') or not r.get('question') for r in inputs):
        raise ValueError('Input requires stable id/question')
    if len({r['id'] for r in inputs}) != len(inputs):
        raise ValueError('Duplicate input IDs')
    if shutil.disk_usage(ROOT.anchor).free < 20 * 1024**3:
        raise OSError('System-volume free space below20GiB')
    package = args.root / 'model'
    manifest = json.loads((package / 'worker.json').read_text(encoding='utf-8-sig'))
    if manifest['evidence'].get('reranker') or manifest['evidence'].get('answerPolicy'):
        raise ValueError('Use RRF without BGE ordering/gate')
    files = []
    for f in manifest['files']:
        actual = digest(package / f['path'])
        if actual != f['sha256']:
            raise ValueError('Model package SHA mismatch')
        files.append({'path': f['path'], 'sha256': actual})
    directory.mkdir()
    output = directory / 'results.jsonl'
    command = [str(args.java), '-Xmx1g', '-XX:-UsePerfData', '-XX:+DisableAttachMechanism', '-XX:ErrorFile=NUL',
               '--enable-native-access=ALL-UNNAMED', '-Denterprise.nativeDlls=true', '-cp', args.classpath,
               'ai.enterprise.research.NeighborContextEvaluation', str(args.root.resolve()), str(args.input.resolve()),
               str(output), str(AREA / 'rules/rules.json'), str(len(inputs))]
    record = {'input_path': str(args.input.resolve()), 'input_sha256': digest(args.input), 'cases': len(inputs),
              'runtime_root': str(args.root.resolve()), 'manifest_sha256': digest(package / 'worker.json'),
              'corpus_sha256': digest(args.root / 'db/index.sqlite'),
              'harness_source_sha256': digest(ROOT / 'src/research/java/ai/enterprise/research/NeighborContextEvaluation.java'),
              'model_files': files, 'command': command, 'java_sha256': digest(args.java),
              'classpath_files': classpath_snapshot(args.classpath), 'rules_sha256': digest(AREA / 'rules/rules.json'),
              'runner_sha256': digest(Path(__file__)), 'generator_called': False, 'final_read': False,
              'state': 'prepared'}
    write_json(directory / 'run.json', record)
    # Same resource lease serializes this CPU-only run with heavyweight GPU/CPU
    # acceptance windows. Acquiring the file does not initialize CUDA.
    with gpu_lease('neighbor-context CPU-only/no-generation ' + args.label):
        with (directory / 'console.log').open('xb') as console:
            process = subprocess.Popen(command, cwd=ROOT, stdout=console, stderr=subprocess.STDOUT)
            record.update(state='running', pid=process.pid)
            write_json(directory / 'run.json', record)
            try:
                result = process.wait()
            finally:
                if process.poll() is None:
                    process.terminate(); process.wait()
        record.update(state='terminal', exit_code=result)
        write_json(directory / 'run.json', record)
        if result:
            raise RuntimeError('Full application neighbor control failed; inspect preserved console.log')
    shutil.copy2(Path(__file__), directory / 'analysis-source.py')
    score(directory)


def mandatory_ids(row):
    for key in ('mandatory_fragment_ids', 'required_fragment_ids', 'mandatory_ids'):
        if key in row:
            return set(row[key]), key
    ids = {a['fragment_id'] for a in row.get('required_atoms', []) if a.get('fragment_id')}
    return ids, 'required_atoms[].fragment_id' if ids else 'unknown'


def score(directory):
    target = directory / 'analysis.json'
    if target.exists():
        raise FileExistsError('Preserve previous neighbor analysis')
    run = json.loads((directory / 'run.json').read_text(encoding='utf-8'))
    input_path = Path(run['input_path'])
    if digest(input_path) != run['input_sha256']:
        raise ValueError('Input changed after run')
    inputs = read_jsonl(input_path); results = read_jsonl(directory / 'results.jsonl')
    if [x['id'] for x in inputs] != [x['id'] for x in results]:
        raise ValueError('Result inventory differs')
    database = Path(run['runtime_root']) / 'db/index.sqlite'
    db = sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True)
    cases = []
    for gold, row in zip(inputs, results, strict=True):
        if row['question'] != gold['question'] or set(row['modules']) != MODULES or len(row['modules']) != 7:
            raise ValueError('Question/full-composition mismatch')
        a, b = row['search_limit10_original'], row['search_limit20_original']
        if not a['success'] or not b['success']:
            cases.append({'id': row['id'], 'valid_control': False, 'error': 'search_failed', 'original_results': row});continue
        neighbors, anchors = a['value'], b['value'][:10]
        neighbor_ids = [x['fragment_id'] for x in neighbors]; anchor_ids = [x['fragment_id'] for x in anchors]
        if len(neighbor_ids) != len(set(neighbor_ids)) or len(anchor_ids) != len(set(anchor_ids)):
            raise ValueError('Duplicate context IDs')
        expand = row['expansion']['expand']
        associations = {}
        for seed in a['value'][:5]:
            located = db.execute('SELECT rowid,document_id FROM chunks WHERE fragment_id=?', (seed['fragment_id'],)).fetchone()
            if not located:
                raise ValueError('Missing canonical seed')
            for sign, direction in (('<', 'DESC'), ('>', 'ASC')):
                adjacent = db.execute(f'SELECT fragment_id,text,source_refs FROM chunks WHERE document_id=? AND rowid{sign}? ORDER BY rowid {direction} LIMIT 1',
                                      (located[1], located[0])).fetchone()
                if adjacent and adjacent[1].strip() and json.loads(adjacent[2] or '[]'):
                    associations.setdefault(adjacent[0], []).append(seed['fragment_id'])
        added = [x for x in neighbor_ids if x not in anchor_ids]
        removed = [x for x in anchor_ids if x not in neighbor_ids]
        association_proof = {x: associations.get(x, []) for x in added}
        valid = (row['anchor_proof']['ranked_at_least_ten_proven'] and row['same_first5_seed_ids'] and all(association_proof.values())) if expand else neighbor_ids == anchor_ids
        mandatory, method = mandatory_ids(gold)
        positives = set(gold.get('positive_ids', []))
        variants = {}
        for label, ids in (('with_neighbors10', neighbor_ids), ('ranked_anchors10', anchor_ids)):
            variants[label] = {'ids': ids, 'context_count': len(ids), 'known_positive_ids_present': sorted(positives & set(ids)),
                               'known_positive_id_recall': len(positives & set(ids)) / len(positives) if positives else None,
                               'mandatory_ids_present': sorted(mandatory & set(ids)),
                               'mandatory_id_coverage': len(mandatory & set(ids)) / len(mandatory) if mandatory else None,
                               'all_known_mandatory_ids_present': mandatory <= set(ids) if mandatory else None}
        cases.append({'id': row['id'], 'question': row['question'], 'language': gold.get('language'),
                      'family_ids': gold.get('family_ids', []), 'expand': expand, 'classification': row['expansion'],
                      'valid_control': valid, 'ranked_candidate_lower_bound': row['anchor_proof']['eligible_dense_candidates_lower_bound'],
                      'first5_seed_ids_equal': row['same_first5_seed_ids'], 'contexts_identical': neighbor_ids == anchor_ids,
                      'added_neighbor_ids': added, 'displaced_ranked_anchor_ids': removed, 'neighbor_to_seed_associations': association_proof,
                      'mandatory_id_definition': method, 'mandatory_ids': sorted(mandatory), 'variants': variants,
                      'answer_quality': 'not_evaluated', 'semantic_completeness': 'unknown'})
    db.close()
    valid = [c for c in cases if c['valid_control']]
    summary = {'cases': len(cases), 'valid_controls': len(valid), 'expanded_cases': sum(c.get('expand', False) for c in cases),
               'nonexpanded_cases': sum(not c.get('expand', False) for c in cases),
               'changed_context_cases': sum(not c['contexts_identical'] for c in valid), 'variants': {}}
    for label in ('with_neighbors10', 'ranked_anchors10'):
        values = [c['variants'][label] for c in valid]
        coverages = [v['mandatory_id_coverage'] for v in values if v['mandatory_id_coverage'] is not None]
        summary['variants'][label] = {'known_positive_any_cases': sum(bool(v['known_positive_ids_present']) for v in values),
                                     'all_known_mandatory_ids_present_cases': sum(v['all_known_mandatory_ids_present'] is True for v in values),
                                     'mandatory_coverage_cases': len(coverages),
                                     'mean_known_mandatory_id_coverage': sum(coverages) / len(coverages) if coverages else None}
    write_json(target, {'scope': 'Evidence-context ID coverage only. No generator, no answer-quality comparison.',
                        'input_sha256': run['input_sha256'], 'results_sha256': digest(directory / 'results.jsonl'),
                        'final_read': False, 'human_verified': False, 'summary': summary, 'cases': cases,
                        'limitations': ['Known positive IDs are not necessarily a complete evidence gold.',
                                        'Mandatory IDs derive only from explicitly identified required spans/fields; unknown is not full coverage.',
                                        'The control replaces lower ranked anchors with neighbors at context size10; it does not measure generator quality.',
                                        'Calls are sequential and share CPU E5. Cold lookup/verification affects first timing; no causal speed claim.']})
    print(json.dumps({'output': str(target), 'summary': summary}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(); commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('prepare')
    runner = commands.add_parser('run'); runner.add_argument('--label', required=True); runner.add_argument('--root', type=Path, required=True)
    runner.add_argument('--input', type=Path, required=True); runner.add_argument('--java', type=Path, required=True); runner.add_argument('--classpath', required=True)
    scorer = commands.add_parser('score'); scorer.add_argument('directory', type=Path)
    args = parser.parse_args()
    if args.command == 'prepare': prepare()
    elif args.command == 'run': run(args)
    else: score(args.directory)


if __name__ == '__main__': main()
