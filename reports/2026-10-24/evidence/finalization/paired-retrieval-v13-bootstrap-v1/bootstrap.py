"""Paired source-coverage bootstrap of the existing, open v13 retrieval run.

Only consumes already-authored source labels. Does not run retrieval, generation,
training or inspect a sealed evaluation. Python standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import random
from collections import Counter
from pathlib import Path


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def read_jsonl(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]


def unique_by(rows, field):
    result = {row[field]: row for row in rows}
    assert len(result) == len(rows), f"Duplicate {field}"
    return result


def percentile(values, probability):
    """Linear interpolation at (n - 1) * p, explicitly fixed for reproduction."""
    ordered = sorted(values)
    index = (len(ordered) - 1) * probability
    lo = int(index)
    hi = min(lo + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (index - lo)


def measure(rows, variant, metric):
    if metric == "source_atom_coverage":
        numerator = sum(row[variant]["covered_atoms"] for row in rows)
        denominator = sum(row["atom_count"] for row in rows)
    elif metric == "all_source_facts_query_coverage":
        numerator = sum(row[variant]["all_atoms_covered"] for row in rows)
        denominator = len(rows)
    else:
        raise ValueError(metric)
    return {"numerator": numerator, "denominator": denominator, "rate": numerator / denominator}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--quality-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--replicates", type=int, default=10_000)
    parser.add_argument("--seed", type=int, default=20261024)
    args = parser.parse_args()
    assert args.replicates > 0
    root = args.quality_root.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    paths = {
        "open_development": root / "retrieval/development-source-verified-v1.jsonl",
        "ru_source_review": root / "retrieval/paired-retrieval-v13-ru-review-v1.json",
        "kz_semantic_labels": root / "host/retrieval-v13/kz-semantic-review/labels.jsonl",
        "kz_review_summary": root / "host/retrieval-v13/kz-semantic-review/summary.json",
    }
    for variant in ("ordinary", "foundation"):
        for filename, key in [("answers.jsonl", "retrieval_output"), ("classpath-inventory.json", "classpath_inventory"), ("run.json", "run")]:
            paths[f"{variant}_{key}"] = root / f"host/retrieval-v13/{variant}/{filename}"
    inputs = {key: {"path": str(path), "bytes": path.stat().st_size, "sha256": sha256(path)} for key, path in paths.items()}
    development = read_jsonl(paths["open_development"])
    dev_by_id = unique_by(development, "id")
    assert len(development) == 28
    assert Counter(row["language"] for row in development) == {"ru": 14, "kz": 14}
    ru_review = read_json(paths["ru_source_review"])
    kz_labels = read_jsonl(paths["kz_semantic_labels"])
    kz_summary = read_json(paths["kz_review_summary"])
    assert ru_review["development_sha256"] == inputs["open_development"]["sha256"]
    assert kz_summary["input_sha256"] == inputs["open_development"]["sha256"]
    assert kz_summary["labels_sha256"] == inputs["kz_semantic_labels"]["sha256"]
    assert ru_review["human_review"] is False and kz_summary["human_review"] is False
    assert inputs["ordinary_classpath_inventory"]["sha256"] == inputs["foundation_classpath_inventory"]["sha256"]

    annotations = {}
    pool_counts = {}
    expected_modules = {"assistant", "database", "inference", "documents", "search", "graph", "sources"}
    for variant in ("ordinary", "foundation"):
        output_path = paths[f"{variant}_retrieval_output"]
        raw_lines = output_path.read_text(encoding="utf-8-sig").splitlines()
        raw = unique_by([json.loads(line) for line in raw_lines if line.strip()], "id")
        raw_line_hash = {json.loads(line)["id"]: hashlib.sha256(line.encode("utf-8")).hexdigest() for line in raw_lines if line.strip()}
        assert set(raw) == set(dev_by_id)
        for case_id, row in raw.items():
            assert row["question"] == dev_by_id[case_id]["question"]
            assert row["mode"] == "search" and set(row["modules"]) == expected_modules
            assert row["result"]["success"] is True
            assert 0 < len(row["result"]["value"]) <= 24
        pool_counts[variant] = {case_id: len(row["result"]["value"]) for case_id, row in raw.items()}
        ru = ru_review["variants"][variant]
        kz = kz_summary["variants"][variant]
        assert ru["input_sha256"] == inputs[f"{variant}_retrieval_output"]["sha256"]
        assert kz["answers_sha256"] == inputs[f"{variant}_retrieval_output"]["sha256"]
        assert ru["classpath_inventory_sha256"] == inputs[f"{variant}_classpath_inventory"]["sha256"]
        assert kz["classpath_inventory_sha256"] == inputs[f"{variant}_classpath_inventory"]["sha256"]
        local = {}
        for case in ru["cases"]:
            assert dev_by_id[case["id"]]["language"] == "ru"
            assert case["pool_units"] == pool_counts[variant][case["id"]]
            facts = unique_by(case["required_source_atoms"], "id")
            assert set(facts) == {atom["id"] for atom in dev_by_id[case["id"]]["required_atoms"]}
            assert all(type(fact["source_quote_found"]) is bool for fact in facts.values())
            local[case["id"]] = {fact_id: "supported" if fact["source_quote_found"] else "not_found" for fact_id, fact in facts.items()}
        variant_kz = [case for case in kz_labels if case["variant"] == variant]
        assert len(variant_kz) == 14
        for case in variant_kz:
            case_id = case["case_id"]
            assert dev_by_id[case_id]["language"] == "kz"
            assert case["actual_line_sha256"] == raw_line_hash[case_id]
            assert case["human_review"] is False and case["automatic"] is True
            assert case["pool_count"] == pool_counts[variant][case_id]
            facts = unique_by(case["facts"], "fact_id")
            assert set(facts) == {atom["id"] for atom in dev_by_id[case_id]["required_atoms"]}
            assert all(fact["status"] in {"supported", "missing", "unknown"} for fact in facts.values())
            local[case_id] = {fact_id: fact["status"] for fact_id, fact in facts.items()}
        assert set(local) == set(dev_by_id)
        annotations[variant] = local

    # Connected components of cases, sharing any family OR question-intent group.
    # A multilingual pair is never sampled as two independent observations.
    parent = {case_id: case_id for case_id in dev_by_id}

    def find(key):
        while key != parent[key]:
            parent[key] = parent[parent[key]]
            key = parent[key]
        return key

    def union(left, right):
        left, right = find(left), find(right)
        if left != right:
            parent[max(left, right)] = min(left, right)

    seen = {}
    for case in development:
        assert case["family_ids"] and case["intent_group"]
        tokens = [("family", family) for family in case["family_ids"]] + [("intent", case["intent_group"])]
        for token in tokens:
            if token in seen:
                union(case["id"], seen[token])
            else:
                seen[token] = case["id"]
    rows = []
    for case in development:
        row = {"id": case["id"], "language": case["language"], "family_ids": case["family_ids"], "intent_group": case["intent_group"], "cluster_id": find(case["id"]), "atom_count": len(case["required_atoms"])}
        assert row["atom_count"] > 0
        for variant in ("ordinary", "foundation"):
            statuses = annotations[variant][case["id"]]
            covered = sum(status == "supported" for status in statuses.values())
            row[variant] = {"covered_atoms": covered, "all_atoms_covered": covered == row["atom_count"], "atom_statuses": statuses, "retrieval_pool_units": pool_counts[variant][case["id"]]}
        rows.append(row)
    clusters = []
    for cluster_id in sorted({row["cluster_id"] for row in rows}):
        members = [row for row in rows if row["cluster_id"] == cluster_id]
        clusters.append(members)
    assert len(clusters) == 5
    for intent in {row["intent_group"] for row in rows}:
        pair = [row for row in rows if row["intent_group"] == intent]
        assert len(pair) == 2 and {row["language"] for row in pair} == {"ru", "kz"}
        assert len({row["cluster_id"] for row in pair}) == 1

    metric_names = ("source_atom_coverage", "all_source_facts_query_coverage")
    rng = random.Random(args.seed)
    draws = [[rng.randrange(len(clusters)) for _ in clusters] for _ in range(args.replicates)]
    draws_bytes = json.dumps(draws, separators=(",", ":")).encode("ascii")
    metrics = {}
    for metric in metric_names:
        point = {variant: measure(rows, variant, metric) for variant in ("ordinary", "foundation")}
        replicates = {"ordinary": [], "foundation": [], "difference": []}
        for draw in draws:
            sampled = [row for cluster_index in draw for row in clusters[cluster_index]]
            scores = {variant: measure(sampled, variant, metric)["rate"] for variant in ("ordinary", "foundation")}
            for variant, value in scores.items():
                replicates[variant].append(value)
            replicates["difference"].append(scores["ordinary"] - scores["foundation"])
        for variant in ("ordinary", "foundation"):
            point[variant]["percentile_95_ci"] = [percentile(replicates[variant], p) for p in (0.025, 0.975)]
        ci = [percentile(replicates["difference"], p) for p in (0.025, 0.975)]
        point["difference_ordinary_minus_foundation"] = {
            "rate": point["ordinary"]["rate"] - point["foundation"]["rate"],
            "percentage_points": 100 * (point["ordinary"]["rate"] - point["foundation"]["rate"]),
            "percentile_95_ci": ci,
            "percentile_95_ci_percentage_points": [100 * value for value in ci],
            "includes_zero": ci[0] <= 0 <= ci[1],
            "bootstrap_draws_equal_zero": sum(value == 0 for value in replicates["difference"]),
            "bootstrap_draws_below_zero": sum(value < 0 for value in replicates["difference"]),
            "confirmed_positive_at_declared_interval": ci[0] > 0,
        }
        metrics[metric] = point
    language_points = {language: {metric: {variant: measure([row for row in rows if row["language"] == language], variant, metric) for variant in ("ordinary", "foundation")} for metric in metric_names} for language in ("ru", "kz")}
    cluster_summary = [{"cluster_id": members[0]["cluster_id"], "family_ids": sorted({family for row in members for family in row["family_ids"]}), "intent_groups": sorted({row["intent_group"] for row in members}), "case_ids": [row["id"] for row in members], "queries": len(members), "atoms": sum(row["atom_count"] for row in members), "metrics": {metric: {variant: measure(members, variant, metric) for variant in ("ordinary", "foundation")} for metric in metric_names}} for members in clusters]
    script = Path(__file__).resolve()
    command = f'python -X utf8 -B "{script}" --quality-root "{root}" --out "{out}" --replicates {args.replicates} --seed {args.seed}'
    result = {
        "schema": "mini-ai-v13-open-paired-source-coverage-bootstrap-v1",
        "status": "COMPLETED_RETRIEVAL_UNCERTAINTY_NOT_ANSWER_ACCEPTANCE",
        "human_review": False,
        "automatic_source_labels": True,
        "scope": "Existing open development28: 14RU+14KZ,14pairedintents,5documentfamilies; final retrieval pools capped at24 units/query (actual counts vary). Not generated-answer quality, actual LLM context Recall@10, or sealed final acceptance.",
        "method": {
            "paired": True,
            "cluster_rule": "Connected components of cases sharing any family_id OR intent_group; preserve all multilingual paraphrases and atoms in the same component. Same sampled components for both variants and both metrics.",
            "clusters": len(clusters), "replicates": args.replicates, "seed": args.seed,
            "rng": "Python random.Random(seed); randrange(K), K draws with replacement per replicate; clusters sorted by lexicographic minimum case id",
            "python": platform.python_version(),
            "interval": "Two-sided percentile95%, quantiles0.025/0.975; linear interpolation index(n-1)*p; no BCa or normal approximation",
            "estimand": "Micro source-atom rate and micro all-source-facts query rate; each replicate recomputes numerator and denominator over sampled whole clusters, rather than averaging family percentages.",
            "unknown_handling": "Only supported/true counts as covered. Missing, unknown, not_found count as not covered. A query is covered only if every required source atom is covered.",
            "draws_sha256": hashlib.sha256(draws_bytes).hexdigest(),
        },
        "inputs": inputs,
        "script": {"path": str(script), "sha256": sha256(script)},
        "reproduce_command": command,
        "input_binding_checks": {"original_question_and_case_ids_exact": True, "original_atom_ids_exact": True, "review_to_raw_and_classpath_sha_exact": True, "kz_raw_line_sha_exact": True, "full_seven_modules_all_56_outputs": True, "all_pools_at_most_24_units_and_counts_exact_to_reviews": True, "all_14_language_pairs_one_cluster": True},
        "pool_count_distributions": {variant: dict(sorted(Counter(counts.values()).items())) for variant, counts in pool_counts.items()},
        "metrics": metrics, "language_points": language_points, "clusters": cluster_summary,
        "limitations": [
            "Only five family-connected clusters: unstable, discrete intervals and substantial zero-difference bootstrap mass. No evidence of benefit outside these families.",
            "This repeatedly used, open development set is not an independent or sealed test. No change to fixed acceptance criteria.",
            "RU exact-source-atom matching and KZ source-linked semantic labels use different necessary language checks; both are automatic, not human verification.",
            "A relevant source in a retrieval pool capped at24 units does not establish that it reaches the eventual generator context, is used correctly, or supports every generated assertion.",
            "All-source-facts query coverage concerns only the finite declared source atoms, not all legal/normative conditions or every applicable document.",
            "This computation reuses existing labels without relabeling, new retrieval, generation, model training, or sealed inputs. It does not isolate every mechanism of the configuration difference.",
        ],
        "recommendation": "Ordinary may remain a provisional open-development candidate. Both paired confidence intervals include zero, so its general advantage is not confirmed by this diagnostic. Confirm or reject with the frozen final evaluation and the untrained control; never label these retrieval rates as answer accuracy.",
    }
    (out / "metrics.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "cases.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    (out / "command.txt").write_text(command + "\n", encoding="utf-8")
    lines = [
        "# Неопределённость v13: найденные исходные факты",
        "",
        "**Это поиск по открытому development-набору, а не качество ответов и не финальная приёмка.** В существующих полных запусках используются 28 вопросов (14 RU / 14 KZ), 14 пар намерений и пять семейств документов; оценивается итоговый пул с лимитом 24 фрагмента на вопрос. Фактический размер некоторых KZ-пулов меньше лимита (ordinary: 4–24; foundation: 10–24); это сохранено в результатах, выдачи не дополнены искусственно.",
        "",
        "| Показатель | ordinary | foundation | Разность, п.п. | Парный 95% кластерный интервал, п.п. |",
        "|---|---:|---:|---:|---:|",
    ]
    for metric, title in [("source_atom_coverage", "Найденные обязательные исходные атомы"), ("all_source_facts_query_coverage", "Вопросы со всеми объявленными исходными атомами")]:
        values = metrics[metric]
        a, b = values["ordinary"], values["foundation"]
        diff = values["difference_ordinary_minus_foundation"]
        lo, hi = diff["percentile_95_ci_percentage_points"]
        lines.append(f'| {title} | {a["numerator"]}/{a["denominator"]} ({100*a["rate"]:.2f}%) | {b["numerator"]}/{b["denominator"]} ({100*b["rate"]:.2f}%) | {diff["percentage_points"]:+.2f} | [{lo:+.2f}; {hi:+.2f}] |')
    lines.extend([
        "",
        "**Оба интервала включают ноль: преимущество не подтверждено этим сравнением.** Ordinary допустим как предварительный кандидат разработки; выбор требует зафиксированной финальной проверки с контролем на исходной E5. Критерии приёмки не менялись.",
        "",
        "Метод: 10 000 парных bootstrap-повторов, seed `20261024`. Сначала вопросы объединены в связные компоненты по любому общему `family_id` **или** `intent_group`; получено пять компонентов. В каждом повторе с возвращением выбраны пять целых компонентов. Одни и те же выборки применены к обеим конфигурациям. Русский и казахский варианты, все условия и атомы одного вопроса сохранены вместе. Доли пересчитаны по суммам числителей и знаменателей выбранных компонентов; это не среднее пяти семейных процентов. Интервал — percentile 2,5/97,5% с линейной интерполяцией.",
        "",
        "`unknown` не считается найденным. Для RU использованы ранее проверенные исходные цитаты, для KZ — ранее составленные автоматические метки смысловой поддержки в найденных исходниках. Это автоматическая проверка, не независимая человеческая оценка. Полностью покрытый вопрос означает наличие всех его объявленных исходных атомов, а не полноту всех применимых нормативных правил.",
        "",
        "Пять семейств дают мало независимых единиц: интервалы дискретны и не доказывают перенос. Набор уже неоднократно использовался в разработке. Наличие факта в поисковом пуле не означает его передачу в контекст генератора или правильное применение в ответе. Результат нельзя называть accuracy, качеством перевода, Recall@10 фактического контекста LLM или доказанным итоговым эффектом обучения.",
        "",
        "Полные числители, интервалы, исходные метки по вопросам, состав компонентов и SHA всех входов: [metrics.json](metrics.json), [cases.jsonl](cases.jsonl). Проверены SHA обеих исходных выдач и classpath-инвентарей, точные вопросы/атомы, построчные SHA KZ, полный состав семи модулей всех 56 выдач и совпадение фактических размеров пулов с исходными рецензиями.",
        "",
        "Воспроизведение: [bootstrap.py](bootstrap.py) и [command.txt](command.txt). Используется только стандартная библиотека Python; исходные данные не изменяются. [Проверенные SHA входов](metrics.json) включают оригинальный набор, обе автоматические рецензии, обе поисковые выдачи, run-манифесты и classpath-инвентари. Модели, GPU, новые данные и закрытый тест не использовались.",
    ])
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    manifest = {"schema": "paired-retrieval-v13-bootstrap-artifacts-v1", "input_sha256": {key: value["sha256"] for key, value in inputs.items()}, "files": [{"path": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)} for path in sorted(out.iterdir()) if path.is_file() and path.name != "manifest.json"]}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"metrics": metrics, "clusters": len(clusters), "metrics_sha256": sha256(out / "metrics.json"), "manifest_sha256": sha256(out / "manifest.json")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
