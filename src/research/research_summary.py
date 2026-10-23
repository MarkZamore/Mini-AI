"""Build a selection-only evidence summary; never opens sealed final questions.

This is a component recommendation, not the parent's end-to-end release freeze.
Only small manifests and already computed development metrics are read.
"""
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / 'src/build/research-20261024'


def read(relative):
    return json.loads((OUT / relative).read_text(encoding='utf-8'))


def reference(relative):
    path = OUT / relative
    return {'path': str(path.relative_to(ROOT)).replace('\\', '/'),
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def link(relative, title=None):
    return f'[{title or relative}]({(OUT / relative).as_posix()})'


def ci(item):
    lo, hi = item['ci95_family_bootstrap']
    return f"{item['difference']:+.6f}; 95% CI [{lo:.6f}; {hi:.6f}]"


def main():
    data = read('data/manifest.json')
    audit = read('data/corpus-audit.json')
    integrity = read('data/data-integrity.json')
    full = read('models/e5/full-comparison.json')
    pilot = read('models/e5/comparison.json')
    native = read('native-pipeline-comparison/comparison.json')
    bge = read('models/bge/evaluation-ordinary/subset-comparison.json')
    policy = read('adequacy-ordinary/policy-candidate.json')
    calibration = read('adequacy-ordinary/summary.json')
    gate = read('adequacy-ordinary/gate-summary.json')
    native_policy = read('candidate-base/policy-decision.json')
    logistic = read('bounded-evidence-pilot/metrics.json')
    exports = {v: read(f'models/e5/{v}/onnx/export-manifest.json')
               for v in ('baseline', 'ordinary', 'hard', 'ordinary-seed2')}
    reports = {v: read(f'models/e5/{v}/training-report.json')
               for v in ('ordinary', 'hard', 'ordinary-seed2')}
    disclosure = [json.loads(line) for line in
                  (OUT / 'data/disclosure-log.jsonl').read_text(encoding='utf-8').splitlines()
                  if line.strip()]
    final_opened = any(r.get('event') == 'final_opened_for_frozen_evaluation'
                       for r in disclosure)
    assert full['replication']['no_best_seed_selection']
    assert integrity['family_disjoint'] and integrity['near_duplicate_count'] == 0
    assert native['all_runs_same_module_manifest']
    assert all(e['parity_passed'] for e in exports.values())

    paths = [
        'data/manifest.json', 'data/corpus-audit.json', 'data/data-integrity.json',
        'data/disclosure-log.jsonl', 'models/e5/comparison.json',
        'models/e5/full-comparison.json', 'native-pipeline-comparison/comparison.json',
        'models/bge/evaluation-ordinary/subset-comparison.json',
        'adequacy-ordinary/policy-candidate.json', 'adequacy-ordinary/summary.json',
        'adequacy-ordinary/gate-summary.json', 'bounded-evidence-pilot/metrics.json',
        'candidate-base/policy-decision.json',
        'models/e5/ordinary/weight-update-proof.json',
        'models/e5/hard/weight-update-proof.json',
        'models/bge/onnx/export-manifest.json',
    ]
    paths += [f'models/e5/{v}/onnx/export-manifest.json' for v in exports]
    paths += [f'models/e5/{v}/training-report.json' for v in reports]
    evidence = [
        {'cause': 'Full-corpus dense retrieval misses source-verified relevant fragments',
         'variant': 'Full encoder FT ordinary versus pinned untrained E5; fixed 106139 pool',
         'metric': full['metrics'], 'paired_ci': full['comparisons'],
         'fact': 'Positive nDCG family CI replicated in seed2; Recall CI includes zero; seed1 fixed before replication',
         'artifacts': ['models/e5/full-comparison.json']},
        {'cause': 'Need to distinguish retrieval engineering from trained-weight contribution',
         'variant': 'Actual full-composition lexical / foundation hybrid / trained hybrid / trained hybrid+BGE8',
         'metric': {k: r['metrics'] for k, r in native['runs'].items()},
         'paired_ci': native['paired_family_bootstrap_95'],
         'fact': 'Recommend trained hybrid without rerank; matched foundation-to-trained hybrid isolates weight contribution with positive nDCG CI, while lexical-to-trained hybrid combines engineering and weights',
         'artifacts': ['native-pipeline-comparison/comparison.json']},
        {'cause': 'Lexically difficult negatives might improve ranking',
         'variant': 'Full encoder FT hard negatives', 'metric': pilot['metrics'],
         'fact': 'No pilot advantage over ordinary; no full-corpus hard-negative claim',
         'artifacts': ['models/e5/comparison.json']},
        {'cause': 'Top-ranked fragments can be reordered incorrectly',
         'variant': 'Frozen BGE reranking top8 / top12 / top24 with preserved tail',
         'metric': bge['metrics'], 'paired_ci': bge['paired_CI'],
         'fact': 'Top8 CI crosses zero; top12 lowers Recall; top24 lowers nDCG. Do not promote BGE ordering',
         'artifacts': ['models/bge/evaluation-ordinary/subset-comparison.json',
                       'native-pipeline-comparison/comparison.json']},
        {'cause': 'Dense score alone can retrieve external topics',
         'variant': 'Provisional BGE top3 relevance rule, fixed before gate20',
         'metric': {'policy': policy, 'calibration_ranges': calibration['ranges'],
                    'gate_modes': gate['policy_modes'], 'actual_native_decision': native_policy},
         'fact': 'Rejected after actual native selection80 coverage loss 7.5pp exceeded preregistered 3pp; homogeneous gate20 alone had hidden this loss. No retuning; external stress labels remain unknown',
         'artifacts': ['adequacy-ordinary/policy-candidate.json',
                       'adequacy-ordinary/gate-summary.json', 'candidate-base/policy-decision.json']},
        {'cause': 'Need an inexpensive context-completeness classifier',
         'variant': 'Bounded provided-context logistic literal-presence pilot',
         'metric': logistic['metrics'],
         'fact': 'Rejected: complete-context coverage 0.5 on selection, 0 on calibration; not a semantic sufficiency model',
         'artifacts': ['bounded-evidence-pilot/metrics.json']},
    ]
    decision = {
        'schemaVersion': 1,
        'scope': 'Data, retrieval and bounded sufficiency research recommendation only',
        'recommendation': {
            'encoder': 'ordinary', 'training_seed': 20261024,
            'retrieval': 'hybrid lexical+dense ordinary E5 without BGE reordering',
            'bge_ordering_promoted': False,
            'bge_top3_relevance_gate': 'rejected: actual native positive coverage loss 7.5pp exceeded preregistered 3pp; thresholds not retuned',
            'bge_in_working_package': False,
            'bounded_logistic_promoted': False,
            'end_to_end_release_freeze': 'owned by parent; not authorized by this component summary',
        },
        'final': {'questions_read_by_this_script': False, 'sha256': data['final_sha256'],
                  'opened_in_disclosure_log': final_opened,
                  'created_by_same_automatic_builder': True,
                  'independent_blind_human_gold': False},
        'data': {'verified_corpus': audit, 'splits': data['splits'],
                 'label_coverage': {'source_verified_question_intents': sum(r['count'] for r in data['splits'].values()),
                                    'independently_verified_semantic_question_gold': 0,
                                    'independent_human_judgments': 0,
                                    'generated_answer_semantic_coverage': 'not established by this retrieval summary'},
                 'integrity': integrity, 'selection_largest_family_questions': 72,
                 'deviations': ['91 distinct train intents rather than the planned minimum 200; variants are not independent intents',
                                'Final 100 RU67/KZ33 rather than 120 RU80/KZ40',
                                'Final 96 extraction and 4 condition cases; broad procedural/exception/conflict gold unavailable',
                                'Only 40 of 599 documents have native-source-verified supervision; language labels automatic',
                                'Selection one family 72/80; final two families dominate; bootstrap does not repair coverage']},
        'training': {'full_base_parameters': 117653760,
                     'steps_per_full_run': {v: r['steps'] for v, r in reports.items()},
                     'independent_train_intents': 91, 'full_epochs_per_run': 1,
                     'staged_gate_optimizer_steps': [2, 16, 23],
                     'checkpoint_state': ['base model', 'optimizer', 'scheduler', 'example order/cursor',
                                          'Python RNG', 'NumPy RNG', 'CPU RNG', 'CUDA RNG'],
                     'resume_limit': 'State restoration and continuation tested; no bitwise uninterrupted-run equivalence claim',
                     'replication': full['replication']},
        'onnx': {v: {k: e[k] for k in ('onnx_sha256', 'onnx_size', 'parity_max_abs',
                                      'parity_min_cosine', 'parity_passed')}
                 for v, e in exports.items()},
        'evidence': evidence,
        'semantic_quality': 'unknown; source-verified literal retrieval proxies do not establish correct sufficiently complete normative answers',
        'independent_human_verification': False,
        'unknown_evaluation_policy': 'Unknown semantic claims remain unknown and are non-success in the conservative lower-bound main score; do not remove from denominator',
        'performance': {k: {m: r[m] for m in ('full_search_seconds_median', 'full_search_seconds_p95', 'timing_meaning')}
                        for k, r in native['runs'].items()},
        'artifacts': [reference(p) for p in paths],
    }
    (OUT / 'selection-decision.json').write_text(
        json.dumps(decision, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    text = [
        '# Данные и выбор компонентов поиска: фактическая сводка',
        '',
        'Рекомендация компонента: гибридный поиск с E5 ordinary, seed 20261024, без BGE-перестановки. '
        'Это решение по development, а не подтверждение качества конечных нормативных ответов и не финальная фиксация всей сборки.',
        '',
        '## Данные и границы проверки',
        '',
        f"Рабочий индекс содержит 106139 фрагментов. Из 599 документов автоматически сверены оригинальные DOCX/XML для {audit['eligible_documents']} документов/{audit['eligible_families']} семейств: 611 блоков и 564 уникальных фрагмента. "
        '58 фрагментов помещены в карантин пяти демонстрационных тем. Язык RU/KZ определён эвристикой, без человека. '
        'XLSX и OCR не получили подтверждённой разметки в этом наборе.',
        '',
        '| Часть | Вопросов | RU/KZ | Семейств |',
        '|---|---:|---:|---:|',
    ]
    for name in ('train', 'selection', 'calibration', 'gatecheck', 'final'):
        r = data['splits'][name]
        text.append(f"| {name} | {r['count']} | {r['languages'].get('ru', 0)}/{r['languages'].get('kz', 0)} | {r['families']} |")
    text += [
        '',
        'Группы семейств не пересекаются; проверка не нашла межчастевых близких копий по заданному критерию. '
        'Однако 72 из 80 selection-вопросов принадлежат одному семейству; в final доминируют два семейства. '
        '100 финальных вопросов включают 30 тематического переноса, 96 извлечений и 4 явных условия. '
        'Это отклонение от 120 RU80/KZ40 и от разнообразного семантического benchmark. '
        '91 обучающий intent не превращается в 200 независимых задач за счёт вариантов контекста.',
        '',
        'Разметка доказывает буквальное присутствие факта в оригинале. Она не доказывает полноту нормы, действующую редакцию, '
        'правильное разрешение противоречий или отсутствие ответа во всём корпусе. Основное семантическое качество остаётся unknown. '
        'Независимой человеческой проверки не было. Неопределимые утверждения нельзя исключать из знаменателя основного показателя: '
        'для консервативной нижней границы это неуспех.',
        '',
        f"Артефакты: {link('data/corpus-audit.json')}, {link('data/manifest.json')}, {link('data/data-integrity.json')}.",
        '',
        '## Причина → вариант → измерение → результат',
        '',
        '**Пропуски dense-поиска.** Полный FT всех 117653760 параметров E5 на 91 тройке: обычные и сложные отрицательные примеры; '
        'этапы 2/16/23 optimizer steps, не более одной эпохи. Сохранены модель, optimizer, scheduler, порядок/cursor и RNG. '
        'Сравнение тензоров подтверждает изменение базовых весов. Возобновление состояния проверено; побитовая эквивалентность непрерывному запуску не заявлена.',
        '',
        '| Полный пул 106139, selection 80 | Recall@10 | nDCG@10 |',
        '|---|---:|---:|',
    ]
    for name, r in full['metrics'].items():
        text.append(f"| {name} | {r['recall_at_10']:.4f} | {r['ndcg_at_10']:.6f} |")
    text += [
        '',
        f"ordinary: ΔnDCG {ci(full['comparisons']['baseline_to_ordinary']['ndcg_at_10'])}; "
        f"ΔRecall {ci(full['comparisons']['baseline_to_ordinary']['recall_at_10'])}. "
        f"seed2: ΔnDCG {ci(full['comparisons']['baseline_to_ordinary-seed2']['ndcg_at_10'])}. "
        'Положительный nDCG-эффект повторился; Recall-интервалы включают 0. Два seed не дают оценки всей вариативности seed. '
        'Seed1 закреплён заранее, выбор лучшего seed после результата не проводился.',
        '',
        f"Артефакт: {link('models/e5/full-comparison.json')}. Сложные отрицательные примеры не дали преимущества на пилотном пуле 564 "
        f"(nDCG ordinary 0.927468, hard 0.896579), поэтому полный hard-индекс не строился: {link('models/e5/comparison.json')}.",
        '',
        '**Проверка настоящего поиска приложения.** Те же 80 вопросов, неизменный полный состав модулей, canonical DB, соседи и namespaces; '
        'по 24 возвращаемых кандидата. Ошибок запросов 0.',
        '',
        '| SearchForAssistant | Recall@10 | nDCG@10 | Поиск median / p95, с |',
        '|---|---:|---:|---:|',
    ]
    for name in ('lexical', 'untrained_hybrid', 'hybrid', 'hybrid_bge8'):
        if name not in native['runs']:
            continue
        r = native['runs'][name]
        text.append(f"| {name} | {r['metrics']['recall_at_10']:.4f} | {r['metrics']['ndcg_at_10']:.6f} | {r['full_search_seconds_median']:.3f} / {r['full_search_seconds_p95']:.3f} |")
    text += [
        '',
        f"lexical→hybrid: ΔnDCG {ci(native['paired_family_bootstrap_95']['lexical_to_hybrid']['ndcg_at_10'])}. "
        'Эта разность объединяет инженерные изменения поиска и обучение. '
        f"Контроль untrained hybrid→trained hybrid изолирует вклад весов при одинаковом native-поиске: ΔnDCG {ci(native['paired_family_bootstrap_95']['untrained_hybrid_to_hybrid']['ndcg_at_10'])}; "
        f"ΔRecall {ci(native['paired_family_bootstrap_95']['untrained_hybrid_to_hybrid']['recall_at_10'])}. "
        'Вопросы автоматически привязаны к документу/фразе, поэтому результат не является точностью на произвольных обычных вопросах. '
        'Задержка здесь относится только к поиску, не к первому токену помощника; возможна сопутствующая нагрузка подготовки.',
        '',
        f"Артефакт: {link('native-pipeline-comparison/comparison.json')}.",
        '',
        '**Ошибки порядка.** Frozen BGE top8 дал небольшой средний прирост, но его family CI пересекает 0; '
        'top12 снизил Recall@10 до 0.9625, top24 снизил nDCG до 0.858168. Переупорядочивание не продвигается. '
        'В фактическом приложении BGE8 также не имеет положительной нижней границы CI и увеличивает задержку поиска.',
        '',
        f"Артефакты: {link('models/bge/evaluation-ordinary/subset-comparison.json')}, {link('native-pipeline-comparison/comparison.json')}.",
        '',
        '**Внешняя тема и недостаточный контекст.** До gate20 закреплено правило по максимальному BGE-logit top3: '
        '≥0 — кандидат adequate; ≤−2.5 либо отсутствующий обязательный буквальный идентификатор — кандидат insufficient; иначе clarify. '
        'Gate20: 20 adequate без перенастройки. Калибровка содержит 20 положительных RU-вопросов одного семейства и 20 внешних stress-вопросов. '
        'Метка «нет ответа во всём корпусе» для stress остаётся unknown; это диагностическое поведение, не измеренная точность отказов. '
        'Логит не вероятность истинности, достаточность нормы и KZ-режимы не подтверждены. '
        'В actual native проверке правило отклонено без перенастройки: на selection80 только 74 answer, 5 clarify и 1 insufficient, '
        'то есть потеря 7.5 п.п. покрытия при допустимых 3 п.п. Однородный gate20 эту проблему не выявил. '
        'В рабочую конфигурацию не включаются ни BGE ordering, ни BGE hard gate; остаются базовые правила и строгая проверка ссылок.',
        '',
        f"Артефакты: {link('adequacy-ordinary/policy-candidate.json')}, {link('adequacy-ordinary/gate-summary.json')}, {link('candidate-base/policy-decision.json')}.",
        '',
        '**Обучаемая дешёвая проверка контекста.** Logistic-пилот проверял только наличие полного буквального атома в предоставленном контексте. '
        'На selection он сохранил лишь 0.5 покрытия полных контекстов, на calibration — 0. Поэтому отклонён. '
        'Это не обученная семантическая достаточность и не основание заявлять корректные отказы.',
        '',
        f"Артефакт: {link('bounded-evidence-pilot/metrics.json')}.",
        '',
        '## Поставка и воспроизводимость',
        '',
        'E5 закреплён на intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3 (MIT). '
        'Оригинальный SentencePiece сохранён. ONNX принимает три int64-входа и возвращает нормализованный 384-мерный embedding; '
        'Torch/CPU ORT parity прошёл RU/KZ, padding и 256 токенов. Frozen BGE закреплён на '
        'BAAI/bge-reranker-v2-m3@953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e (Apache-2.0). '
        'Его ONNX требует все внешние sidecar-файлы с неизменными именами и возвращает raw logits.',
        '',
        f"Выбранный E5 ONNX SHA256: `{exports['ordinary']['onnx_sha256']}`. "
        f"Артефакты: {link('models/e5/ordinary/onnx/export-manifest.json')}, {link('models/bge/onnx/export-manifest.json')}.",
        '',
        'Базовый, ordinary и seed2 индексы готовы для 106139 фрагментов. '
        'Значение minScore=−1 в исследовательском index.json является техническим placeholder, а не выбранной политикой достаточности. '
        'Полная сборка, генератор, prompt, пороги и финальная оценка фиксируются родительским процессом отдельно.',
        '',
        f"Финальные вопросы этой сводкой не читались; зарегистрированное открытие final: {'да' if final_opened else 'нет'}. "
        'Создатель автоматического final не является независимым слепым оценщиком. Старый test и пять демонстрационных тем — только diagnostics. '
        f"Сводная машинная запись и SHA малых доказательных файлов: {link('selection-decision.json')}.",
    ]
    (OUT / 'selection-summary.md').write_text('\n'.join(text) + '\n', encoding='utf-8')
    print(json.dumps({'decision': str(OUT / 'selection-decision.json'),
                      'markdown': str(OUT / 'selection-summary.md'),
                      'final_questions_read': False,
                      'final_opened_in_disclosure_log': final_opened}, ensure_ascii=False))


if __name__ == '__main__':
    main()
