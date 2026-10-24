"""CPU-only, family-disjoint answer-mode research; never reads a final set.

The small predeclared comparison is fixed rules, regularized logistic regression,
and shallow gradient boosting. Features see only the question and supplied
canonical retrieval evidence. Gold atoms, annotations, IDs, interventions and
expected modes cannot enter the feature vector. No probabilities are presented
as correctness of generated normative text.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / 'src/build/research-20261024/quality-v2'
WORK = RESEARCH / 'classical-models'
RESERVE = RESEARCH / 'evaluation/public/reserved-families-v1.json'
RESERVE_SHA = '4ba29fd097aae0b91c2f4b7332b9a598b80cfccce1bc62a32ff6c7192fbfc715'
MODES = ('answer', 'clarify', 'insufficient')
SEED = 20261024
FEATURE_NAMES = (
    'question_tokens', 'question_chars', 'question_specific_token_ratio',
    'question_deictic', 'question_temporal', 'question_actor',
    'evidence_count', 'evidence_nonempty_count', 'evidence_total_tokens',
    'evidence_max_tokens', 'distinct_document_count', 'warning_fraction',
    'partial_fraction', 'question_overlap_max', 'question_overlap_mean',
    'evidence_question_overlap_max', 'dense_score_available_fraction',
    'dense_score_max', 'dense_score_mean', 'lexical_score_available_fraction',
    'lexical_score_max', 'lexical_score_mean', 'kazakh_letter_fraction',
)
STOPWORDS = set('а и в во на по о об от до из за к ко с со для не ни ли же бы это как какой какая какие что где когда кто почему зачем можно нужно надо пожалуйста мне мы у он она оно они the a an of to in and or is are'.split())
TOKEN = re.compile(r'[^\W_]+', re.UNICODE)
DEICTIC = re.compile(r'\b(это|этого|этому|такой|такие|тогда|он|она|они|его|её|их|осы|оны|оның|бұл|сол)\b', re.I)
TEMPORAL = re.compile(r'\b(когда|срок|сроки|дней|день|недел\w*|месяц\w*|жыл\w*|күн\w*|мерзім\w*|қашан)\b', re.I)
ACTOR = re.compile(r'\b(кто|кому|кем|куда|кто\w*|ким|кім|кімге|қайда)\b', re.I)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def json_write(path, payload):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def read_development_rows(path):
    path = Path(path).resolve()
    if any('sealed' in part.casefold() for part in path.parts) or 'final' in path.name.casefold():
        raise ValueError('Classical training must not open final or sealed datasets')
    if not path.is_relative_to(RESEARCH.resolve()):
        raise ValueError('Use an explicit quality-v2 development dataset')
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def words(text):
    return TOKEN.findall(str(text).casefold())


def number(value):
    try:
        value = float(value)
    except (TypeError, ValueError):
        return None
    return value if math.isfinite(value) else None


def features(row):
    """Strict projection: annotations and gold text are never inspected here."""
    question = str(row.get('question', ''))
    evidence = row.get('retrieved_evidence', [])
    if not isinstance(evidence, list):
        raise ValueError('retrieved_evidence must be a list')
    tokens = words(question)
    query = set(tokens) - STOPWORDS
    passages = [words(item.get('text', '')) for item in evidence]
    significant = [set(passage) - STOPWORDS for passage in passages]
    overlaps = [len(query & passage) / max(1, len(query)) for passage in significant]
    reverse = [len(query & passage) / max(1, len(passage)) for passage in significant]
    scores = {}
    for name in ('dense_score', 'lexical_score'):
        values = [number(item.get(name)) for item in evidence]
        finite = [value for value in values if value is not None]
        scores[name] = (len(finite) / max(1, len(evidence)), max(finite, default=0.), sum(finite) / max(1, len(finite)))
    warnings = sum(bool(item.get('source_warnings')) for item in evidence)
    partial = sum(str(item.get('quality_status', '')).casefold() == 'partial' for item in evidence)
    documents = {str(item['document_id']) for item in evidence if item.get('document_id')}
    dense, lexical = scores['dense_score'], scores['lexical_score']
    vector = (
        len(tokens), len(question), len(query) / max(1, len(set(tokens))),
        float(bool(DEICTIC.search(question))), float(bool(TEMPORAL.search(question))), float(bool(ACTOR.search(question))),
        len(evidence), sum(bool(passage) for passage in passages), sum(map(len, passages)),
        max(map(len, passages), default=0), len(documents), warnings / max(1, len(evidence)),
        partial / max(1, len(evidence)), max(overlaps, default=0.), sum(overlaps) / max(1, len(overlaps)),
        max(reverse, default=0.), *dense, *lexical,
        sum(char in 'әғқңөұүһіӘҒҚҢӨҰҮҺІ' for char in question) / max(1, len(question)),
    )
    if len(vector) != len(FEATURE_NAMES) or not all(math.isfinite(float(value)) for value in vector):
        raise ValueError('Nonfinite or incompatible runtime feature vector')
    return [float(value) for value in vector]


def fixed_rule(vector):
    f = dict(zip(FEATURE_NAMES, vector))
    if f['question_tokens'] <= 7 and f['question_deictic']:
        return 'clarify'
    if f['evidence_nonempty_count'] == 0:
        return 'insufficient'
    if f['question_overlap_max'] < .05 and f['dense_score_available_fraction'] == 0:
        return 'insufficient'
    return 'answer'


def reservation(path=RESERVE):
    if digest(path) != RESERVE_SHA:
        raise ValueError('Reserved-family authority SHA changed; independent audit required')
    return read_json(path)


def identifiers(row):
    families = set(map(str, row.get('family_ids', [])))
    documents = set(map(str, row.get('document_ids', [])))
    for source in row.get('retrieved_evidence', []):
        if source.get('family_id'):
            families.add(str(source['family_id']))
        if source.get('document_id'):
            documents.add(str(source['document_id']))
    return families, documents


def validate_rows(rows, reserve):
    accepted, excluded = [], []
    ids = set()
    reserved_families = set(reserve['reserved_family_ids'])
    reserved_documents = set(reserve['reserved_document_ids'])
    for row in rows:
        if not row.get('id') or row['id'] in ids:
            raise ValueError('Missing or duplicate case id')
        ids.add(row['id'])
        families, documents = identifiers(row)
        if families & reserved_families or documents & reserved_documents:
            raise ValueError('Reserved final family/document present: ' + row['id'])
        if not row.get('intent_group'):
            raise ValueError('Explicit intent grouping is required')
        if row.get('retrieved_evidence') and (not families or not documents):
            raise ValueError('Source-bound rows require canonical family/document identities')
        if row.get('expected_mode') not in MODES or row.get('label_status') != 'verified':
            excluded.append({'id': row['id'], 'reason': 'Unknown or unverified mode label; never treated as a positive'})
            continue
        if row['expected_mode'] == 'insufficient' and row.get('label_scope') != 'provided_context':
            raise ValueError('Insufficient labels must be bounded to supplied evidence')
        if not row.get('label_rationale'):
            raise ValueError('Verified mode requires source-linked label rationale')
        accepted.append(row)
    return accepted, excluded


def connected_groups(rows):
    """Unify shared families, documents, declared intents and identical questions."""
    parents = list(range(len(rows)))

    def find(index):
        while parents[index] != index:
            parents[index] = parents[parents[index]]
            index = parents[index]
        return index

    seen = {}
    for index, row in enumerate(rows):
        families, documents = identifiers(row)
        keys = ['family:' + value for value in families] + ['document:' + value for value in documents]
        keys += ['intent:' + str(row['intent_group']), 'question:' + ' '.join(words(row['question']))]
        for key in keys:
            if key in seen:
                parents[find(index)] = find(seen[key])
            else:
                seen[key] = index
    raw = [find(index) for index in range(len(rows))]
    roots = {value: number for number, value in enumerate(sorted(set(raw)))}
    return ['component-%03d' % roots[value] for value in raw]


def metrics(truth, predicted):
    confusion = [[0] * len(MODES) for _ in MODES]
    for actual, guess in zip(truth, predicted):
        confusion[MODES.index(actual)][MODES.index(guess)] += 1
    f1 = []
    for index in range(len(MODES)):
        tp = confusion[index][index]
        total_true = sum(confusion[index])
        total_pred = sum(row[index] for row in confusion)
        f1.append(2 * tp / max(1, total_true + total_pred))
    answer_true = sum(actual == 'answer' for actual in truth)
    negative_true = len(truth) - answer_true
    accepts = sum(guess == 'answer' for guess in predicted)
    false_accept = sum(actual != 'answer' and guess == 'answer' for actual, guess in zip(truth, predicted))
    false_refusal = sum(actual == 'answer' and guess != 'answer' for actual, guess in zip(truth, predicted))
    return {
        'macro_f1': sum(f1) / len(MODES),
        'accuracy_diagnostic_only': sum(actual == guess for actual, guess in zip(truth, predicted)) / max(1, len(truth)),
        'false_accept_rate': false_accept / negative_true if negative_true else None,
        'false_refusal_rate': false_refusal / answer_true if answer_true else None,
        'answer_coverage': accepts / max(1, len(truth)),
        'answer_precision': (accepts - false_accept) / accepts if accepts else None,
        'false_accept_count': false_accept, 'false_refusal_count': false_refusal,
        'counts': dict(collections.Counter(truth)), 'confusion_labels': list(MODES), 'confusion': confusion,
        'scope': 'Classification of answer mode under supplied evidence; not generated-answer correctness.',
    }


def plan():
    return {
        'schema_version': 2, 'feature_version': 'question-evidence-numeric-v1', 'seed': SEED,
        'previous_unexecuted_plan_sha256': '748cd58638e6920df8875090d1539b2b8614e040183885f7cfc06f35dc48249d',
        'revision_reason': 'Before any fit, correct the multiclass total-tree budget: 32 stages * 3 classes = 96 trees. Earlier 64-stage plan would exceed the requested total of 100; no model outputs used.',
        'methods': ['fixed_rules', 'logistic', 'gradient_boosting'], 'feature_names': list(FEATURE_NAMES),
        'feature_exclusions': ['gold_required_atoms', 'expected_mode', 'label_status', 'label_scope', 'rationale', 'family/document/fragment identifiers', 'context intervention labels'],
        'fixed_rules': {'short_deictic_tokens_max': 7, 'minimum_question_overlap_without_dense': .05, 'zero_evidence': 'insufficient'},
        'logistic': {'C': .3, 'solver': 'lbfgs', 'max_iter': 1000, 'class_weight': 'balanced', 'scaling': 'StandardScaler fit only on fold train'},
        'gradient_boosting': {'n_estimators': 32, 'maximum_total_trees_three_classes': 96, 'learning_rate': .05, 'max_depth': 2, 'min_samples_leaf': 4, 'sample_weight': 'balanced within fold train'},
        'cross_validation': {'maximum_folds': 5, 'splitter': 'StratifiedGroupKFold', 'grouping': 'Connected components of all source families/documents, declared intents and identical normalized questions'},
        'bootstrap': {'replicates': 10000, 'cluster': 'Same connected source/intent groups as CV', 'report_ci': .95, 'export_gate_ci': .975, 'multiplicity_note': 'Two model-vs-rules comparisons; gate uses 97.5% percentile intervals, nominal Bonferroni coverage, not proof with very few clusters.'},
        'export_gate': {'minimum_independent_groups': 8, 'minimum_cases_each_class': 10, 'minimum_macro_f1_gain': .05, 'macro_f1_gain_gate_ci_lower_strictly_positive': True, 'max_false_accept_delta_gate_ci_upper': .03, 'max_false_refusal_delta_gate_ci_upper': .03, 'minimum_coverage_delta_gate_ci_lower': -.03},
        'reserve_path': str(RESERVE), 'reserve_sha256': RESERVE_SHA,
        'no_final_or_GPU': True, 'probabilities_are_not_normative_correctness': True,
    }


def install_local_path():
    deps = WORK / 'python-deps'
    if deps.is_dir():
        sys.path.insert(0, str(deps))
    os.environ.setdefault('OMP_NUM_THREADS', '2')
    os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
    os.environ.setdefault('MKL_NUM_THREADS', '2')


def bootstrap(truth, predictions, groups):
    import numpy as np
    clusters = sorted(set(groups))
    members = {group: [index for index, value in enumerate(groups) if value == group] for group in clusters}
    rng = np.random.default_rng(SEED)
    keys = ('macro_f1', 'false_accept_rate', 'false_refusal_rate', 'answer_coverage')
    samples = {name: {key: [] for key in keys} for name in predictions if name != 'fixed_rules'}
    for _ in range(plan()['bootstrap']['replicates']):
        indices = [index for cluster in rng.choice(clusters, size=len(clusters), replace=True) for index in members[cluster]]
        actual = [truth[index] for index in indices]
        base = metrics(actual, [predictions['fixed_rules'][index] for index in indices])
        for name in samples:
            result = metrics(actual, [predictions[name][index] for index in indices])
            for key in keys:
                if result[key] is not None and base[key] is not None:
                    samples[name][key].append(result[key] - base[key])
    output = {}
    for name, data in samples.items():
        output[name] = {}
        measured = metrics(truth, predictions[name])
        base = metrics(truth, predictions['fixed_rules'])
        for key, values in data.items():
            output[name][key] = {'difference_model_minus_rules': measured[key] - base[key] if measured[key] is not None and base[key] is not None else None,
                                 'ci95': np.quantile(values, [.025, .975]).tolist() if values else None,
                                 'ci97_5_export_gate': np.quantile(values, [.0125, .9875]).tolist() if values else None,
                                 'valid_replicates': len(values), 'clusters': len(clusters)}
    return output


def export_model(model, kind):
    if kind == 'logistic':
        scaler, classifier = model.named_steps['scale'], model.named_steps['model']
        return {'method': kind, 'classes': classifier.classes_.tolist(), 'mean': scaler.mean_.tolist(), 'scale': scaler.scale_.tolist(), 'coefficients': classifier.coef_.tolist(), 'intercept': classifier.intercept_.tolist(), 'decision': 'argmax linear logit; standardized numeric features; probabilities not exposed'}
    trees = []
    for stage in model.estimators_:
        trees.append([{'children_left': tree.tree_.children_left.tolist(), 'children_right': tree.tree_.children_right.tolist(),
                       'feature': tree.tree_.feature.tolist(), 'threshold': tree.tree_.threshold.tolist(),
                       'value': tree.tree_.value[:, 0, 0].tolist()} for tree in stage])
    return {'method': kind, 'classes': model.classes_.tolist(), 'learning_rate': model.learning_rate,
            'initial_class_prior': model.init_.class_prior_.tolist(), 'trees': trees,
            'decision': 'log class priors plus learning_rate*tree values, argmax; no probability of normative correctness'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['plan', 'prepare', 'fit', 'self-check'])
    parser.add_argument('--dataset', type=Path)
    parser.add_argument('--source-audit', type=Path)
    parser.add_argument('--reserve', type=Path, default=RESERVE)
    parser.add_argument('--out', type=Path, default=WORK / 'pilot-v1')
    args = parser.parse_args()
    if args.command == 'self-check':
        self_check()
        return
    registered = WORK / 'preregistered-v2.json'
    if registered.exists() and read_json(registered) != plan():
        raise ValueError('Predeclared methods changed; preserve prior plan and create a new revision')
    if not registered.exists():
        json_write(registered, plan())
    if args.command == 'plan':
        print(json.dumps({'status': 'REGISTERED', 'path': str(registered), 'sha256': digest(registered)}))
        return
    if not args.dataset:
        raise ValueError('Explicit non-final dataset is required')
    raw = read_development_rows(args.dataset)
    rows, excluded = validate_rows(raw, reservation(args.reserve))
    groups = connected_groups(rows)
    matrix = [features(row) for row in rows]
    labels = [row['expected_mode'] for row in rows]
    result = {'status': 'PREPARED', 'dataset': str(args.dataset.resolve()), 'dataset_sha256': digest(args.dataset),
              'source_audit': str(args.source_audit.resolve()) if args.source_audit else None,
              'source_audit_sha256': digest(args.source_audit) if args.source_audit else None,
              'plan_sha256': digest(registered), 'code_sha256': digest(__file__), 'reserve_sha256': RESERVE_SHA,
              'cases': len(rows), 'excluded_unknown_or_unverified': excluded, 'groups': len(set(groups)), 'label_counts': dict(collections.Counter(labels)),
              'feature_names': list(FEATURE_NAMES), 'rows': [{'id': row['id'], 'group': group, 'expected_mode': row['expected_mode'], 'features': vector} for row, group, vector in zip(rows, groups, matrix)],
              'scope': 'Development-only classification under supplied canonical evidence; no generated-answer quality acceptance.'}
    if args.command == 'prepare':
        json_write(args.out / 'prepared.json', result)
        print(json.dumps({key: result[key] for key in ('status', 'cases', 'groups', 'label_counts', 'excluded_unknown_or_unverified')}))
        return
    if not args.source_audit or read_json(args.source_audit).get('status') != 'PASS':
        raise ValueError('Fit requires a separate source-audit PASS artifact')
    audit = read_json(args.source_audit)
    if audit.get('dataset_sha256') != digest(args.dataset):
        raise ValueError('Source audit does not pin this exact dataset')
    if not {row['id'] for row in rows} <= set(audit.get('approved_ids', [])):
        raise ValueError('Every fitted label must be explicitly approved by the separate source reviewer; unknown is not positive')
    if (args.out / 'run-manifest.json').exists():
        raise FileExistsError('Preserve completed results; choose a new output directory')
    if set(labels) != set(MODES):
        raise ValueError('All three verified modes required; no automatic fabricated labels')
    class_groups = {label: len({group for group, y in zip(groups, labels) if y == label}) for label in MODES}
    folds = min(5, *class_groups.values())
    if folds < 2:
        raise ValueError('Too few disjoint source/intent groups for a model comparison')
    install_local_path()
    import numpy as np
    import scipy
    import sklearn
    import joblib
    from sklearn.ensemble import GradientBoostingClassifier
    from sklearn.linear_model import LogisticRegression
    from sklearn.model_selection import StratifiedGroupKFold
    from sklearn.pipeline import Pipeline
    from sklearn.preprocessing import StandardScaler
    from sklearn.utils.class_weight import compute_sample_weight
    from threadpoolctl import threadpool_limits
    X, y = np.asarray(matrix, dtype=float), np.asarray(labels)
    splitter = StratifiedGroupKFold(n_splits=folds, shuffle=True, random_state=SEED)

    def create(name):
        if name == 'logistic':
            return Pipeline([('scale', StandardScaler()), ('model', LogisticRegression(C=.3, solver='lbfgs', max_iter=1000, class_weight='balanced', random_state=SEED))])
        return GradientBoostingClassifier(n_estimators=32, learning_rate=.05, max_depth=2, min_samples_leaf=4, random_state=SEED)

    predictions = {'fixed_rules': [fixed_rule(vector) for vector in matrix], 'logistic': [None] * len(rows), 'gradient_boosting': [None] * len(rows)}
    fold_records = []
    with threadpool_limits(limits=2):
        for fold, (train, test) in enumerate(splitter.split(X, y, groups)):
            if set(y[train]) != set(MODES):
                raise ValueError('A fold lacks a training class; cannot claim a valid uniform comparison')
            if set(groups[index] for index in train) & set(groups[index] for index in test):
                raise AssertionError('Source/intent group leakage')
            fold_records.append({'fold': fold, 'train_ids': [rows[index]['id'] for index in train], 'test_ids': [rows[index]['id'] for index in test], 'train_groups': sorted({groups[index] for index in train}), 'test_groups': sorted({groups[index] for index in test})})
            for name in ('logistic', 'gradient_boosting'):
                model = create(name)
                if name == 'gradient_boosting':
                    model.fit(X[train], y[train], sample_weight=compute_sample_weight('balanced', y[train]))
                else:
                    model.fit(X[train], y[train])
                for index, guess in zip(test, model.predict(X[test])):
                    predictions[name][int(index)] = str(guess)
        assert all(all(guess is not None for guess in values) for values in predictions.values())
        scores = {name: metrics(labels, values) for name, values in predictions.items()}
        intervals = bootstrap(labels, predictions, groups)
        eligible = []
        gate = plan()['export_gate']
        eligibility = {}
        for name in ('logistic', 'gradient_boosting'):
            delta = intervals[name]
            checks = {
                'enough_groups': len(set(groups)) >= gate['minimum_independent_groups'],
                'enough_labels': min(collections.Counter(labels).values()) >= gate['minimum_cases_each_class'],
                'meaningful_f1_gain': delta['macro_f1']['difference_model_minus_rules'] >= .05,
                'f1_interval_positive': delta['macro_f1']['ci97_5_export_gate'][0] > 0,
                'false_accept_noninferiority': delta['false_accept_rate']['ci97_5_export_gate'][1] <= .03,
                'false_refusal_noninferiority': delta['false_refusal_rate']['ci97_5_export_gate'][1] <= .03,
                'coverage_noninferiority': delta['answer_coverage']['ci97_5_export_gate'][0] >= -.03,
            }
            eligibility[name] = checks
            if all(checks.values()):
                eligible.append(name)
        selected = max(eligible, key=lambda name: (scores[name]['macro_f1'], name == 'logistic')) if eligible else None
        saved_models = []
        for name in ('logistic', 'gradient_boosting'):
            model = create(name)
            if name == 'gradient_boosting':
                model.fit(X, y, sample_weight=compute_sample_weight('balanced', y))
            else:
                model.fit(X, y)
            path = args.out / 'research-models' / (name + '.joblib')
            path.parent.mkdir(parents=True, exist_ok=True)
            joblib.dump(model, path, compress=3)
            saved_models.append({'name': name, 'path': str(path.resolve()), 'sha256': digest(path), 'bytes': path.stat().st_size, 'production_installed': False})
            if name == selected:
                payload = export_model(model, name)
                payload.update(feature_names=list(FEATURE_NAMES), feature_version=plan()['feature_version'], dataset_sha256=digest(args.dataset), normative_quality_acceptance=False)
                json_write(args.out / 'candidate-export.json', payload)
    result.update(status='COMPLETED', folds=fold_records, methods=scores, paired_group_bootstrap=intervals,
                  candidate_export=selected, candidate_eligibility=eligibility, saved_research_models=saved_models,
                  versions={'python': sys.version, 'numpy': np.__version__, 'scipy': scipy.__version__, 'sklearn': sklearn.__version__, 'joblib': joblib.__version__},
                  limitations=['Automatic source-bound mode labels, not human semantic gold', 'Group CV and bootstrap on a small corpus are not an external final test', 'No probabilities are correctness estimates', 'No production files, final tests, GPU or generated answers used'])
    args.out.mkdir(parents=True, exist_ok=True)
    json_write(args.out / 'metrics.json', result)
    json_write(args.out / 'folds.json', fold_records)
    with (args.out / 'oof-predictions.jsonl').open('w', encoding='utf-8') as out:
        for index, row in enumerate(rows):
            out.write(json.dumps({'id': row['id'], 'group': groups[index], 'expected_mode': labels[index], 'predictions': {name: values[index] for name, values in predictions.items()}}, ensure_ascii=False) + '\n')
    json_write(args.out / 'run-manifest.json', {'status': 'COMPLETED', 'dataset_sha256': digest(args.dataset), 'plan_sha256': digest(registered), 'code_sha256': digest(__file__), 'metrics_sha256': digest(args.out / 'metrics.json'), 'candidate_export': selected, 'new_final_read': False, 'production_changed': False})
    print(json.dumps({'status': 'COMPLETED', 'cases': len(rows), 'groups': len(set(groups)), 'methods': scores, 'candidate_export': selected}, ensure_ascii=False))


def self_check():
    row = {'id': 'sample', 'question': 'Кто подаёт заявление?', 'intent_group': 'one', 'family_ids': ['safe'], 'document_ids': ['doc'], 'expected_mode': 'answer', 'label_status': 'verified', 'label_rationale': 'source clause', 'retrieved_evidence': [{'text': 'Работник подаёт заявление.', 'document_id': 'doc', 'family_id': 'safe'}]}
    baseline = features(row)
    changed_gold = dict(row, required_atoms=['SECRET LABEL'], forbidden_assertions=['nothing'], expected_mode='insufficient', label_scope='provided_context', family_ids=['other'])
    assert features(changed_gold) == baseline
    assert all(math.isfinite(value) for value in features(dict(row, retrieved_evidence=[{'text': '', 'dense_score': float('nan')}])) )
    empty = dict(row, question='Какие документы нужны для поступления?', retrieved_evidence=[])
    assert fixed_rule(features(empty)) == 'insufficient'
    assert fixed_rule(features(dict(row, question='А это кому?', retrieved_evidence=[]))) == 'clarify'
    duplicate = dict(row, id='other', family_ids=['second'])
    assert len(set(connected_groups([row, duplicate]))) == 1
    reserve = {'reserved_family_ids': ['blocked'], 'reserved_document_ids': []}
    injected = dict(row, retrieved_evidence=[{'text': 'text', 'family_id': 'blocked', 'document_id': 'doc'}])
    try:
        validate_rows([injected], reserve)
    except ValueError:
        pass
    else:
        raise AssertionError('Nested reserved family was not rejected')
    accepted, excluded = validate_rows([dict(row, label_status='unknown')], reserve)
    assert not accepted and len(excluded) == 1
    print(json.dumps({'status': 'PASS', 'checks': 7, 'scope': 'Feature gold-independence, finite runtime values, bounded refusal, grouping and reserve/unknown guards only; no ML quality result.'}))


if __name__ == '__main__':
    main()
