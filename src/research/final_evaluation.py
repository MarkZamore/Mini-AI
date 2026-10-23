"""Predeclared, conservative evaluation of frozen full-application runs.

``prepare`` and ``freeze`` never read the sealed final file. ``open`` and
``score`` require an explicit root authorization and revalidate every frozen
artifact before reading it. This evaluator grades mechanical source/atom
properties; it does not manufacture independent semantic labels.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import random
import re
import sqlite3
import statistics


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "src/build/research-20261024"
DATA = RESEARCH / "data"
MODULES = {"assistant", "database", "documents", "graph", "inference", "search", "sources"}
AUTHORIZATION = "root-final-configuration-frozen"
MARKER = re.compile(r"\[source:([^\]\r\n]+)\]")
SEED = 20261024
BOOTSTRAP_SAMPLES = 10000
HEADINGS = ("краткий ответ", "условия или порядок действий", "порядок действий",
            "что делать дальше", "основание", "условия")


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def value_sha(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")).encode("utf-8")).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def read_rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8-sig").splitlines()
            if line.strip()]


def write(path, value):
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def write_rows(path, rows):
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def norm(text):
    return " ".join(str(text).split()).casefold()


def absolute(path):
    path = Path(path)
    return (path if path.is_absolute() else ROOT / path).resolve()


def record(path):
    path = absolute(path)
    before = path.stat()
    result = {"path": str(path), "bytes": before.st_size, "sha256": sha(path)}
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError(f"Artifact changed while hashing: {path}")
    return result


def inventory(paths):
    files = set()
    for raw in paths:
        path = absolute(raw)
        if not path.exists():
            raise FileNotFoundError(path)
        if path.is_dir():
            files.update(child.resolve() for child in path.rglob("*") if child.is_file())
        else:
            files.add(path)
    if any("sealed-final" in path.parts for path in files):
        raise ValueError("Do not include sealed questions/labels in the freeze inventory")
    return [record(path) for path in sorted(files, key=lambda p: str(p).casefold())]


def schema_check(rows, expected_split):
    ids = set()
    for row in rows:
        required = {"id", "question", "language", "family_ids", "intent_group", "positive_ids", "required_atoms"}
        if not required <= row.keys() or row.get("split") != expected_split:
            raise ValueError("Question schema/split mismatch")
        if row["id"] in ids or not row["family_ids"] or not row["required_atoms"]:
            raise ValueError("Duplicate ID, empty family or empty atom rubric")
        ids.add(row["id"])
        if any(not atom.get("text") or not atom.get("fragment_id") for atom in row["required_atoms"]):
            raise ValueError("Malformed atom rubric")


def definitions():
    return {
        "primary": "Independent semantic success: supported answer, >=90% of independently validated mandatory facts/conditions, no known critical error. No independent semantic labels exist here: all cases unknown, conservative success zero; refusals/unknown remain non-success.",
        "literal_atom_coverage": "Exact whitespace-normalized case-folded substring presence of source-verified literal spans in the actual final visible history answer; NOT semantic completeness or paraphrase grading.",
        "cited_literal_atom_coverage": "Literal atom present and its exact canonical source cited in the actual supplied context; DOES NOT establish applicability or entailment.",
        "critical_errors": "Only mechanical citation/source failures are determinable. Semantic critical additions, deadlines, applicability, obligations and exceptions remain unknown. Suspicious residual numbers/words are candidates for review, never automatic semantic error labels.",
        "retrieval": "Known positive fragment Recall@5/10 and binary nDCG@10 in actual ordered context or actual search results. No relevance is inferred for unlabelled fragments.",
        "citations": "Separate current-context membership, canonical SQLite existence/identity and literal source-atom association; no semantic entailment claim.",
        "coverage": "Generation attempted and visible source-cited answer are measured separately; detected deterministic refusal/clarification is not a correct-answer pass.",
        "timing": "Real first nonempty token/done event and full request time. Empty/status frames do not count. Report answer length and cold-first case separately; neither shorter output nor refusal proves compute acceleration.",
        "intervals": "Paired 10000-draw 95% percentile bootstrap, resampling connected document-family clusters; also family-then-intent hierarchical sensitivity. Differences are right-minus-left. Intervals containing zero mean advantage not confirmed; few/dominant families severely limit scope.",
        "transfer": "Held-out thematic subset within the same university corpus; does not prove quality for a second organization/corpus.",
        "source_gold": "Automatically matched original DOCX XML spans; independent human review, semantic completeness and external assessor absent.",
    }


PROTOCOL = """# Зафиксированный протокол финальной оценки

До разрешения root этот файл и команда `prepare` используют только development и
публичные метаданные разбиения. Sealed final не читается и не хешируется.

1. Root выбирает генератор по development и фиксирует полный состав из семи
   модулей, runtime/EXE, CUDA-библиотеки, веса, индекс, prompt/code, decoding,
   thresholds, source DB и команду запуска в спецификации freeze. Для каждого
   контроля заранее записывается единственное исследуемое отличие. Основной
   baseline обозначается точно: историческое приложение и matched component
   control — разные сравнения.
2. `freeze --spec SPEC.json --out FREEZE.json` требует authorization
   `root-final-configuration-frozen`; проверяет конфигурации и SHA. Эта команда
   ещё не открывает final. `open --freeze FREEZE.json --out NEW_DIR` повторно
   проверяет inventory и пишет журнал раскрытия, затем создаёт input.jsonl,
   содержащий только id/question. Нельзя применять ответы final для настройки.
3. Root последовательно запускает RagEvaluation answer/search с теми же frozen
   параметрами и всеми семью модулями. Каждый вопрос — новая Session. GPU lease
   и Teams/resource режим фиксируются отдельно. Сохраняются raw JSONL, полные
   видимые answers, фактические ordered contexts, ошибки и события; повторные
   запуски/неудачи не перезаписываются. До первого запуска не менять порядок.
4. `score --freeze FREEZE.json --run LABEL=answers.jsonl --search-run
   LABEL=search.jsonl --out NEW_DIR` вновь проверяет freeze и sealed SHA;
   неизвестные/дублированные ID и неполный состав запрещены. Отсутствующие случаи
   остаются в знаменателе и дают failure/unknown; неполный run явно помечается.
   Сравнения используются только предварительно перечисленные в freeze.
5. Отдельно публикуются RU/KZ, семьи, типы и 30 тематических transfer случаев;
   literal atoms/цитаты/Recall/nDCG/coverage/ошибки/latency. Main semantic metric
   и semantic critical errors остаются unknown, conservative main success=0.
   Наличие цитаты и совпадение текста не заменяют смысловую проверку. 100 случаев
   преимущественно phrase/document-assisted extraction, не широкий тест обычных
   вопросов, процедур, отсутствия ответа, конфликтов и исключений. Два семейства
   доминируют; даже CI bootstrap не устраняет это ограничение. Transfer внутри
   университетской базы не означает перенос на другую организацию.
6. Для причинного вывода о generator FT требуются одинаковые ordered evidence,
   code/decoding/quantization и разные только веса; для E5 — тот же generator и
   полный pipeline, замена только encoder+соответствующий derived index. Не
   приписывать CPU→GPU/lexical→hybrid весь эффект обучению. При CI, включающем 0,
   писать «преимущество не подтверждено». После исправлений по final нужен новый
   тест; этот набор остаётся использованным, новые ответы не являются повторной
   независимой приёмкой.

## Предел честного вывода

Возможны выводы о поиске известных буквальных оснований, существовании ссылок,
воспроизведении явно указанных атомов, устойчивости выполнения и измеренной
задержке. Невозможно подтвердить +10 п.п. общего нормативного качества, >=90%
семантической полноты или отсутствие критических смысловых ошибок этим gold.
Автоматический автор теста и Codex не являются независимым человеком-оценщиком.
"""


def prepare(out):
    if out.exists():
        raise FileExistsError("Use a fresh preparation directory")
    selection = read_rows(DATA / "selection.jsonl")
    schema_check(selection, "selection")
    metadata = load(DATA / "manifest.json")
    out.mkdir(parents=True)
    write(out / "preparation.json", {
        "state": "prepared_without_final_access", "final_read": False,
        "development_cases_inspected": len(selection),
        "development_family_counts": dict(Counter(f for row in selection for f in row["family_ids"])),
        "development_schema_keys": sorted(selection[0]),
        "final_metadata_only": metadata["splits"]["final"],
        "expected_sealed_sha256_from_public_manifest": metadata["final_sha256"],
        "definitions": definitions(), "evaluator": record(__file__),
    })
    template = {
        "authorization": "REPLACE_ONLY_AFTER_ROOT_FREEZES_CONFIGURATION",
        "corpus_database": "db/index.sqlite", "code_paths": ["src/core", "src/modules", "src/model-worker", "src/research/java", "src/research/final_evaluation.py"],
        "portable_release": {"configuration_id": "REPLACE_WITH_FINAL_ID", "app_path": "REPLACE_WITH_FINAL_EXE",
                             "model_manifest_path": "REPLACE_WITH_FINAL_MODEL_MANIFEST"},
        "reproduce_files": ["REPLACE_WITH_COMPILED_CLASSES_NATIVE_APP_AND_RUN_COMMAND_FILE"],
        "runner": {"entry_point": "ai.enterprise.research.RagEvaluation", "modes": ["answer", "search"],
                   "module_ids": sorted(MODULES), "same_new_session_per_question": True,
                   "decoding": "greedy", "context_tokens": 16384, "max_output_tokens": 1536,
                   "teams_mode": "REPLACE_WITH_ACTUAL_MODE", "commands": "REPLACE_WITH_EXACT_COMMANDS"},
        "thresholds": {"semantic_minimum_mandatory_coverage": 0.9, "requested_primary_gain": 0.10,
                       "maximum_coverage_regression": 0.03, "bootstrap_samples": BOOTSTRAP_SAMPLES, "seed": SEED},
        "configs": {"baseline": {"role": "original_baseline", "description": "REPLACE_WITH_PRECISE_BASELINE",
                                 "runtime_root": "REPLACE_WITH_RUNTIME", "extra_files": []},
                    "final": {"role": "final", "description": "REPLACE_WITH_FROZEN_WINNER",
                              "runtime_root": "REPLACE_WITH_RUNTIME", "extra_files": []}},
        "comparisons": [{"left": "baseline", "right": "final", "effect": "complete_application_configuration",
                         "context_equality_required": False}],
    }
    write(out / "freeze-spec-template.json", template)
    (out / "ПРОТОКОЛ.md").write_text(PROTOCOL, encoding="utf-8")
    print(json.dumps({"out": str(out), "final_read": False, "development_cases": len(selection)}, ensure_ascii=False))


def freeze(spec_path, out):
    if out.exists():
        raise FileExistsError("A freeze is immutable; use a new path")
    spec = load(spec_path)
    if spec.get("authorization") != AUTHORIZATION:
        raise ValueError("Explicit root final configuration freeze authorization missing")
    if "REPLACE_" in json.dumps(spec):
        raise ValueError("Freeze specification still contains placeholders")
    for key in ("configs", "comparisons", "runner", "thresholds", "code_paths", "reproduce_files", "corpus_database", "portable_release"):
        if not spec.get(key):
            raise ValueError(f"Missing nonempty freeze field: {key}")
    if set(spec["runner"].get("module_ids", [])) != MODULES:
        raise ValueError("Only complete seven-module evaluation is allowed")
    if spec["thresholds"].get("bootstrap_samples") != BOOTSTRAP_SAMPLES or spec["thresholds"].get("seed") != SEED:
        raise ValueError("Use the predeclared bootstrap budget and seed")
    roles = {config.get("role") for config in spec["configs"].values()}
    if "final" not in roles or not roles & {"original_baseline", "matched_baseline"}:
        raise ValueError("A precise baseline and final configuration must be declared")
    for pair in spec["comparisons"]:
        if pair.get("left") not in spec["configs"] or pair.get("right") not in spec["configs"] or not pair.get("effect"):
            raise ValueError("Malformed predeclared comparison")
        if not isinstance(pair.get("context_equality_required"), bool):
            raise ValueError("Declare whether exact context equality is required")
    metadata = load(DATA / "manifest.json")
    database = record(spec["corpus_database"])
    release = spec["portable_release"]
    for key in ("configuration_id", "app_path", "model_manifest_path"):
        if not release.get(key):
            raise ValueError(f"Missing portable_release field: {key}")
    app = record(release["app_path"])
    model_manifest = record(release["model_manifest_path"])
    configs = {}
    for label, config in spec["configs"].items():
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", label):
            raise ValueError("Configuration labels must be simple safe file labels")
        runtime = absolute(config["runtime_root"])
        required = [runtime / "model", runtime / "native", runtime / "db/index.sqlite"]
        if sha(required[-1]) != database["sha256"]:
            raise ValueError("Every runtime must use the same frozen canonical corpus")
        files = required + [absolute(path) for path in config.get("extra_files", [])]
        if (runtime / "db/base.json").exists():
            files.append(runtime / "db/base.json")
        if (runtime / "db/documents").exists():
            files.append(runtime / "db/documents")
        configs[label] = {**config, "runtime_root": str(runtime),
                          "inventory_paths": [str(path) for path in files], "inventory": inventory(files)}
    result = {
        "schema_version": 1, "state": "final-frozen", "configuration_id": release["configuration_id"],
        "app_sha256": app["sha256"], "model_manifest_sha256": model_manifest["sha256"],
        "corpus_sha256": database["sha256"], "app": app, "model_manifest": model_manifest,
        "authorization": AUTHORIZATION, "created_utc": datetime.now(timezone.utc).isoformat(),
        "final_read": False, "specification": record(spec_path),
        "dataset_manifest": record(DATA / "manifest.json"),
        "sealed_sha256_from_public_manifest": metadata["final_sha256"],
        "expected_final_composition": metadata["splits"]["final"],
        "sources": record(DATA / "sources.jsonl"), "corpus_database": database,
        "development_inventory": inventory([DATA / (part + ".jsonl") for part in
            ("train", "selection", "calibration", "gatecheck")] + [DATA / "family-assignment.json", DATA / "data-integrity.json"]),
        "code_paths": [str(absolute(path)) for path in spec["code_paths"]],
        "reproduce_paths": [str(absolute(path)) for path in spec["reproduce_files"]],
        "code_inventory": inventory(spec["code_paths"]), "reproduce_inventory": inventory(spec["reproduce_files"]),
        "evaluator": record(__file__), "configs": configs,
        "runner": spec["runner"], "thresholds": spec["thresholds"],
        "comparisons": spec["comparisons"], "metric_definitions": definitions(),
        "independent_semantic_gold": False, "human_review": False,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    write(out, result)
    print(json.dumps({"freeze": str(out), "sha256": sha(out), "final_read": False,
                      "configs": list(configs)}, ensure_ascii=False))


def validate_freeze(path):
    frozen = load(path)
    if frozen.get("state") != "final-frozen" or frozen.get("authorization") != AUTHORIZATION:
        raise ValueError("Missing authorized pre-final freeze")
    records = [frozen[key] for key in ("specification", "dataset_manifest", "sources", "corpus_database", "evaluator", "app", "model_manifest")]
    records += frozen["development_inventory"]
    records += frozen["code_inventory"] + frozen["reproduce_inventory"]
    records += [item for config in frozen["configs"].values() for item in config["inventory"]]
    inventories = [(frozen["code_paths"], frozen["code_inventory"]),
                   (frozen["reproduce_paths"], frozen["reproduce_inventory"])]
    inventories += [(config["inventory_paths"], config["inventory"]) for config in frozen["configs"].values()]
    for paths, items in inventories:
        actual_paths = set()
        for name in paths:
            directory = Path(name)
            actual_paths.update(str(path.resolve()) for path in directory.rglob("*") if path.is_file()) if directory.is_dir() else actual_paths.add(str(directory.resolve()))
        if actual_paths != {item["path"] for item in items}:
            raise ValueError("Files were added or removed after the configuration freeze")
    checked = {}
    for item in records:
        previous = checked.get(item["path"])
        if previous and previous != item["sha256"]:
            raise ValueError("Frozen artifact has conflicting hashes")
        if not previous:
            if record(item["path"]) != item:
                raise ValueError(f"Frozen artifact changed: {item['path']}")
            checked[item["path"]] = item["sha256"]
    return frozen


def disclose(freeze_path, purpose):
    frozen = validate_freeze(freeze_path)
    event = {"event": "final_open_requested_for_frozen_evaluation", "purpose": purpose,
             "freeze_sha256": sha(freeze_path), "utc": datetime.now(timezone.utc).isoformat(),
             "must_not_be_used_for_further_tuning": True}
    with (DATA / "disclosure-log.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(event, ensure_ascii=False) + "\n")
    # This is the first final-file access in this module. Preparation and freeze
    # use only the expected digest already published in the unsealed manifest.
    raw = (DATA / "sealed-final/final.jsonl").read_bytes()
    if hashlib.sha256(raw).hexdigest() != frozen["sealed_sha256_from_public_manifest"]:
        raise ValueError("Sealed final SHA differs from predeclared dataset")
    rows = [json.loads(line) for line in raw.decode("utf-8-sig").splitlines() if line.strip()]
    schema_check(rows, "final")
    final_families = {family for row in rows for family in row["family_ids"]}
    final_ids = {row["id"] for row in rows}
    final_intents = {row["intent_group"] for row in rows}
    for part in ("train", "selection", "calibration", "gatecheck"):
        other = read_rows(DATA / (part + ".jsonl"))
        if final_families & {family for row in other for family in row["family_ids"]}:
            raise ValueError("Final families overlap training/development")
        if final_ids & {row["id"] for row in other} or final_intents & {row["intent_group"] for row in other}:
            raise ValueError("Final IDs/intents overlap training/development")
    expected = frozen["expected_final_composition"]
    actual = {"count": len(rows), "languages": dict(Counter(row["language"] for row in rows)),
              "families": len({family for row in rows for family in row["family_ids"]}),
              "tasks": dict(Counter(row.get("task") for row in rows)),
              "thematic_transfer": sum(bool(row.get("thematic_transfer")) for row in rows)}
    if actual != expected:
        raise ValueError("Final composition differs from predeclared public metadata")
    with (DATA / "disclosure-log.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({**event, "event": "final_opened_for_frozen_evaluation",
                                 "final_sha256": frozen["sealed_sha256_from_public_manifest"],
                                 "cases": len(rows)}, ensure_ascii=False) + "\n")
    return frozen, rows


def open_final(freeze_path, out):
    if out.exists():
        raise FileExistsError("Keep previous disclosed inputs; use a fresh directory")
    frozen, rows = disclose(freeze_path, "question_only_runner_input")
    out.mkdir(parents=True)
    write_rows(out / "input.jsonl", [{"id": row["id"], "question": row["question"]} for row in rows])
    write(out / "input-manifest.json", {"freeze_sha256": sha(freeze_path),
        "sealed_sha256": frozen["sealed_sha256_from_public_manifest"],
        "input_sha256": sha(out / "input.jsonl"), "cases": len(rows),
        "must_not_be_used_for_further_tuning": True})
    print(json.dumps({"input": str(out / "input.jsonl"), "cases": len(rows)}, ensure_ascii=False))


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def quantile(values, q):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * q
    left = int(position)
    right = min(left + 1, len(values) - 1)
    return values[left] + (values[right] - values[left]) * (position - left)


def distribution(values):
    values = [float(value) for value in values if finite(value)]
    return {"count": len(values), "median": statistics.median(values) if values else None,
            "p95": quantile(values, .95), "minimum": min(values) if values else None,
            "maximum": max(values) if values else None}


def retrieval(ids, positives):
    if len(ids) != len(set(ids)):
        raise ValueError("Actual context/search contains duplicate canonical fragment IDs")
    positives = set(positives)
    result = {}
    for k in (5, 10):
        relevant = [int(value in positives) for value in ids[:k]]
        result[f"recall_at_{k}"] = sum(relevant) / len(positives) if positives else None
        ideal = sum(1 / math.log2(rank + 2) for rank in range(min(k, len(positives))))
        result[f"ndcg_at_{k}"] = sum(rel / math.log2(rank + 2) for rank, rel in enumerate(relevant)) / ideal if ideal else None
        result[f"all_known_evidence_at_{k}"] = bool(positives and positives <= set(ids[:k]))
    return result


def context_check(sources, database):
    seen = set()
    invalid = []
    for source in sources:
        fragment = source.get("fragment_id", "")
        if not fragment or fragment in seen:
            raise ValueError("Empty/duplicate actual source ID")
        seen.add(fragment)
        row = database.execute("SELECT document_id,text,source_sha256,source_refs FROM chunks WHERE fragment_id=?",
                               (fragment,)).fetchone()
        if row is None:
            invalid.append({"fragment_id": fragment, "reason": "canonical_fragment_missing"})
        elif (source.get("document_id"), source.get("text", ""), source.get("source_sha256"), source.get("source_refs")) != (row[0], row[1], row[2], json.loads(row[3])):
            invalid.append({"fragment_id": fragment, "reason": "canonical_identity_text_or_locators_differ"})
    return invalid


def score_case(gold, raw, verified_sources, database):
    if raw is not None and raw.get("question") != gold["question"]:
        raise ValueError("Runner changed a frozen question")
    if raw is not None and (len(raw.get("modules", [])) != 7 or set(raw["modules"]) != MODULES):
        raise ValueError("Only full seven-module runs are admissible")
    raw = raw or {}
    history = [message for message in raw.get("history", []) if message.get("role") == "assistant"]
    response = history[-1] if history else {}
    text = response.get("text", "")
    if not isinstance(text, str):
        raise ValueError("Assistant history text must be a string")
    sources = response.get("sources", [])
    context_ids = [source.get("fragment_id") for source in sources]
    invalid_sources = context_check(sources, database)
    citations = MARKER.findall(text)
    invalid_citations = [citation for citation in citations if citation not in context_ids]
    actual_context_known = bool(history)
    citation_valid = not invalid_citations and not invalid_sources if actual_context_known else None
    # Application warnings are retained in the original answer artifact but are
    # not evaluated as if the model had asserted them as normative content.
    content, delimiter, warning = text.partition("\n\nОграничения источников:")
    plain = norm(MARKER.sub("", content))
    atoms = []
    for atom in gold["required_atoms"]:
        source = verified_sources.get(atom["fragment_id"])
        if source is None or norm(atom["text"]) not in norm(source["text"]):
            raise ValueError("Atom is not a verified literal span in the source snapshot")
        present = norm(atom["text"]) in plain
        atoms.append({"fragment_id": atom["fragment_id"], "literal_present": present,
                      "positive_citation_present": atom["fragment_id"] in citations,
                      "source_in_actual_context": atom["fragment_id"] in context_ids,
                      "source_verified_literal_span": True,
                      "semantic_paraphrase_and_completeness": "unknown"})
    residual = plain
    for atom in sorted(gold["required_atoms"], key=lambda atom: len(atom["text"]), reverse=True):
        residual = residual.replace(norm(atom["text"]), " ")
    for heading in HEADINGS:
        residual = residual.replace(heading, " ")
    residual_words = re.findall(r"\w+", residual, flags=re.UNICODE)
    events = raw.get("events", [])
    errors = [event.get("error") for event in events if event.get("error")]
    if raw.get("result", {}).get("error"):
        errors.append(raw["result"]["error"])
    error_codes = sorted({str(error.get("code", "UNKNOWN")) for error in errors if isinstance(error, dict)})
    states = [event for event in events if event.get("type") == "state"]
    generation = next((event.get("atSeconds") for event in states if str(event.get("text", "")).startswith("Подготовка модели и ожидание ответа")), None)
    refusal = bool(text and not citations and (
        content.startswith("В текущей базе не найдены фрагменты, подтверждающие запрошенные нормы.")
        or content.startswith("Найденные фрагменты не дают достаточного основания")
        or content.startswith("Чтобы определить применимое правило, уточните")))
    literal_count = sum(atom["literal_present"] for atom in atoms)
    cited_count = sum(atom["literal_present"] and atom["positive_citation_present"] and atom["source_in_actual_context"] for atom in atoms)
    first_event = next((event.get("atSeconds") for event in events if event.get("type") in ("token", "done") and str(event.get("text", "")).strip()), None)
    recorded_first = raw.get("firstNonemptyTextSeconds")
    if finite(first_event) and finite(recorded_first) and abs(first_event - recorded_first) > .001:
        raise ValueError("Recorded first visible text does not match actual nonempty token/done events")
    result = {
        "id": gold["id"], "language": gold["language"], "family_ids": gold["family_ids"],
        "intent_group": gold["intent_group"], "task": gold.get("task"),
        "thematic_transfer": bool(gold.get("thematic_transfer")), "missing_result": not bool(raw),
        "request_success": raw.get("result", {}).get("success") is True and not errors,
        "error_codes": error_codes, "assistant_state": response.get("state"),
        "answer_chars": len(text), "answer_words": len(re.findall(r"\w+", text)),
        "source_warning_suffix": warning if delimiter else None,
        "actual_context_provided": actual_context_known, "context_ids": context_ids,
        "ordered_context_sha256": value_sha(sources),
        "ordered_evidence_sha256": value_sha([{key: source.get(key) for key in
            ("fragment_id", "document_id", "text", "source_sha256", "source_refs", "base_id", "content_version")}
            for source in sources]),
        "invalid_sources": invalid_sources, "citations": citations,
        "invalid_citations": invalid_citations, "citation_inventory_valid": citation_valid,
        "citation_present": bool(citations), "positive_citation_present": bool(set(citations) & set(gold["positive_ids"])),
        "atom_results": atoms, "literal_atoms_present": literal_count,
        "literal_atoms_total": len(atoms), "literal_atom_coverage": literal_count / len(atoms),
        "cited_literal_atom_coverage": cited_count / len(atoms),
        "all_literal_atoms_present": literal_count == len(atoms),
        "all_literal_atoms_cited": cited_count == len(atoms),
        "additional_content_status": "unknown" if residual_words else "none_detected_by_literal_rubric",
        "candidate_critical_addition_tokens": [token for token in residual_words if re.search(r"\d|срок|долж|обязан|исключ|мерзім|міндет", token)],
        "candidate_additions_are_proven_errors": False,
        "detected_deterministic_refusal_or_clarification": refusal,
        "generation_attempted": generation is not None,
        "visible_source_cited_answer": bool(text and citations and not refusal),
        "incomplete_answer_warning": content.startswith("Ответ неполон:"),
        "first_visible_seconds": first_event, "full_seconds": raw.get("fullSeconds"),
        "generation_started_seconds": generation,
        "semantic_success": "unknown", "semantic_completeness": "unknown",
        "semantic_critical_error_status": "unknown", "primary_success_conservative": 0.0,
        "human_review": False,
    }
    result.update(retrieval(context_ids, gold["positive_ids"]))
    result["strict_literal_proxy_success"] = bool(result["request_success"] and text and citation_valid is True
        and cited_count == len(atoms) and not residual_words and not refusal)
    return result


def summarize(rows):
    n = len(rows)
    metric_names = ("literal_atom_coverage", "cited_literal_atom_coverage", "recall_at_5", "recall_at_10", "ndcg_at_10")
    rates = {metric: statistics.mean(row[metric] for row in rows if row[metric] is not None)
             if any(row[metric] is not None for row in rows) else None for metric in metric_names}
    rates.update({metric + "_rate": sum(bool(row[metric]) for row in rows) / n if n else None
                  for metric in ("request_success", "visible_source_cited_answer", "positive_citation_present",
                                 "strict_literal_proxy_success", "detected_deterministic_refusal_or_clarification",
                                 "generation_attempted", "incomplete_answer_warning")})
    return {"cases": n, "missing_results": sum(row["missing_result"] for row in rows),
            "run_complete": not any(row["missing_result"] for row in rows),
            "family_counts": dict(Counter(f for row in rows for f in row["family_ids"])),
            "language_counts": dict(Counter(row["language"] for row in rows)),
            "task_counts": dict(Counter(row["task"] for row in rows)),
            "rates": rates,
            "literal_atoms_present": sum(row["literal_atoms_present"] for row in rows),
            "literal_atoms_total": sum(row["literal_atoms_total"] for row in rows),
            "mechanically_invalid_citation_answers": sum(bool(row["invalid_citations"] or row["invalid_sources"]) for row in rows),
            "unknown_citation_inventory_answers": sum(row["citation_inventory_valid"] is None for row in rows),
            "unknown_semantic_success_cases": n, "semantic_success_conservative_rate": 0.0,
            "known_semantic_success_cases": 0, "semantic_success_among_semantically_verified": None,
            "semantic_critical_error_rate": None, "semantic_critical_error_unknown_cases": n,
            "error_codes": dict(Counter(code for row in rows for code in row["error_codes"])),
            "first_visible_seconds": distribution([row["first_visible_seconds"] for row in rows]),
            "full_seconds": distribution([row["full_seconds"] for row in rows]),
            "answer_chars": distribution([row["answer_chars"] for row in rows]),
            "answered_full_seconds": distribution([row["full_seconds"] for row in rows if row["visible_source_cited_answer"]]),
            "cold_first_case": {key: rows[0].get(key) for key in ("id", "first_visible_seconds", "full_seconds", "answer_chars")} if rows else None}


def clusters(rows):
    # Union families sharing an intent (or a multi-document case), so one
    # correlated intent can never be split across independently sampled groups.
    parent = {}
    def find(key):
        parent.setdefault(key, key)
        if parent[key] != key:
            parent[key] = find(parent[key])
        return parent[key]
    def union(left, right):
        a, b = find(left), find(right)
        parent[max(a, b)] = min(a, b)
    first_intent = {}
    for row in rows:
        families = row["family_ids"]
        for family in families:
            union(families[0], family)
        if row["intent_group"] in first_intent:
            union(families[0], first_intent[row["intent_group"]])
        first_intent[row["intent_group"]] = families[0]
    grouped = defaultdict(lambda: defaultdict(list))
    for index, row in enumerate(rows):
        grouped[find(row["family_ids"][0])][row["intent_group"]].append(index)
    return {family: dict(intents) for family, intents in sorted(grouped.items())}


def paired_bootstrap(left, right, metric, label):
    if [row["id"] for row in left] != [row["id"] for row in right]:
        raise ValueError("Paired inventory mismatch")
    keep = [index for index in range(len(left)) if isinstance(left[index].get(metric), (bool, int, float))
            and isinstance(right[index].get(metric), (bool, int, float))
            and math.isfinite(left[index][metric]) and math.isfinite(right[index][metric])]
    if not keep:
        return {"metric": metric, "paired_cases": 0, "ci95_family_bootstrap": None,
                "ci95_hierarchical_family_intent": None, "advantage_confirmed": False}
    aa = [left[index] for index in keep]
    differences = [float(right[index][metric]) - float(left[index][metric]) for index in keep]
    grouped = clusters(aa)
    keys = sorted(grouped)
    stable_seed = int(hashlib.sha256(f"{SEED}:{label}:{metric}".encode()).hexdigest()[:16], 16)
    rng = random.Random(stable_seed)
    family_values, hierarchical_values = [], []
    for _ in range(BOOTSTRAP_SAMPLES):
        chosen = rng.choices(keys, k=len(keys))
        flat = [index for family in chosen for indices in grouped[family].values() for index in indices]
        family_values.append(sum(differences[index] for index in flat) / len(flat))
        hierarchy = []
        for family in chosen:
            intent_keys = sorted(grouped[family])
            for intent in rng.choices(intent_keys, k=len(intent_keys)):
                hierarchy.extend(grouped[family][intent])
        hierarchical_values.append(sum(differences[index] for index in hierarchy) / len(hierarchy))
    family_ci = [quantile(family_values, .025), quantile(family_values, .975)]
    hierarchical_ci = [quantile(hierarchical_values, .025), quantile(hierarchical_values, .975)]
    return {"metric": metric, "difference_right_minus_left": statistics.mean(differences),
            "ci95_family_bootstrap": family_ci, "ci95_hierarchical_family_intent": hierarchical_ci,
            "paired_cases": len(keep), "excluded_missing_numeric_pairs": len(left) - len(keep),
            "family_clusters": len(keys), "intent_groups": sum(len(value) for value in grouped.values()),
            "bootstrap_samples": BOOTSTRAP_SAMPLES, "seed": stable_seed,
            "positive_family_interval": family_ci[0] > 0,
            "negative_family_interval": family_ci[1] < 0,
            "interval_includes_zero": family_ci[0] <= 0 <= family_ci[1],
            "advantage_confirmed": False if metric == "primary_success_conservative" else None,
            "interpretation": "Proxy-specific interval only; no independent semantic-quality claim"}


def results_by_label(items, frozen):
    result = {}
    for item in items or []:
        if "=" not in item:
            raise ValueError("Use LABEL=path.jsonl")
        label, filename = item.split("=", 1)
        if label not in frozen["configs"] or label in result:
            raise ValueError("Unknown or repeated frozen configuration label")
        result[label] = absolute(filename)
    return result


def validate_results(path, gold_by_id, expected_mode):
    rows = read_rows(path)
    result = {}
    for row in rows:
        if row.get("id") not in gold_by_id or row["id"] in result:
            raise ValueError("Unknown or duplicate result ID")
        if row.get("mode") != expected_mode or row.get("question") != gold_by_id[row["id"]]["question"]:
            raise ValueError("Wrong run mode or altered question")
        if len(row.get("modules", [])) != 7 or set(row["modules"]) != MODULES:
            raise ValueError("Incomplete application composition")
        result[row["id"]] = row
    return result


def score(freeze_path, run_items, search_items, out):
    if out.exists():
        raise FileExistsError("Do not overwrite a previous evaluation")
    frozen, gold = disclose(freeze_path, "mechanical_scoring_without_independent_semantic_labels")
    gold_by_id = {row["id"]: row for row in gold}
    sources = {row["fragment_id"]: row for row in read_rows(frozen["sources"]["path"])}
    answers = results_by_label(run_items, frozen)
    searches = results_by_label(search_items, frozen)
    if not answers:
        raise ValueError("At least one answer run is required")
    db_uri = Path(frozen["corpus_database"]["path"]).as_uri() + "?mode=ro&immutable=1"
    database = sqlite3.connect(db_uri, uri=True)
    runs = {}
    try:
        for label, path in answers.items():
            raw = validate_results(path, gold_by_id, "answer")
            cases = [score_case(example, raw.get(example["id"]), sources, database) for example in gold]
            run = {"answer_artifact": record(path), "cases": cases, "overall": summarize(cases)}
            run["subsets"] = {
                "ru": summarize([row for row in cases if row["language"] == "ru"]),
                "kz": summarize([row for row in cases if row["language"] == "kz"]),
                "thematic_transfer": summarize([row for row in cases if row["thematic_transfer"]]),
                "non_transfer": summarize([row for row in cases if not row["thematic_transfer"]]),
                "families": {family: summarize([row for row in cases if family in row["family_ids"]])
                             for family in sorted({family for row in cases for family in row["family_ids"]})},
                "tasks": {task: summarize([row for row in cases if row["task"] == task])
                          for task in sorted({row["task"] for row in cases})},
            }
            if label in searches:
                raw_search = validate_results(searches[label], gold_by_id, "search")
                search_cases = []
                for example in gold:
                    row = raw_search.get(example["id"], {})
                    value = row.get("result", {}).get("value") or []
                    ids = [source["fragment_id"] for source in value]
                    search_cases.append({"id": example["id"], "family_ids": example["family_ids"],
                        "intent_group": example["intent_group"], "thematic_transfer": bool(example.get("thematic_transfer")),
                        "language": example["language"], "request_success": row.get("result", {}).get("success") is True,
                        "missing_result": not bool(row), "top_ids": ids, "full_seconds": row.get("fullSeconds"),
                        "invalid_sources": context_check(value, database),
                        **retrieval(ids, example["positive_ids"])})
                run["search_artifact"] = record(searches[label])
                run["search_cases"] = search_cases
                run["search_summary"] = {"cases": len(search_cases),
                    "missing_results": sum(row["missing_result"] for row in search_cases),
                    "request_errors": sum(not row["request_success"] for row in search_cases),
                    "metrics": {metric: statistics.mean(row[metric] for row in search_cases)
                                for metric in ("recall_at_5", "recall_at_10", "ndcg_at_10")},
                    "full_search_seconds": distribution([row["full_seconds"] for row in search_cases])}
            runs[label] = run
    finally:
        database.close()
    comparisons = []
    paired_metrics = ("primary_success_conservative", "literal_atom_coverage", "cited_literal_atom_coverage",
                      "positive_citation_present", "visible_source_cited_answer", "recall_at_10", "ndcg_at_10",
                      "first_visible_seconds", "full_seconds", "answer_chars")
    for declared in frozen["comparisons"]:
        left, right = declared["left"], declared["right"]
        if left not in runs or right not in runs:
            comparisons.append({**declared, "state": "not_evaluated_missing_configuration"})
            continue
        aa, bb = runs[left]["cases"], runs[right]["cases"]
        same_context = sum(not a["missing_result"] and not b["missing_result"] and
                           a["ordered_context_sha256"] == b["ordered_context_sha256"] for a, b in zip(aa, bb))
        same_reduced_evidence = sum(not a["missing_result"] and not b["missing_result"] and
                           a["ordered_evidence_sha256"] == b["ordered_evidence_sha256"] for a, b in zip(aa, bb))
        pair = {**declared, "state": "evaluated", "identical_ordered_evidence_cases": same_context,
                "identical_reduced_evidence_cases": same_reduced_evidence,
                "context_control_satisfied": same_context == len(gold) if declared["context_equality_required"] else None,
                "metrics": {metric: paired_bootstrap(aa, bb, metric, left + "->" + right) for metric in paired_metrics},
                "independent_semantic_improvement": "not_established_unknown_gold"}
        if "search_cases" in runs[left] and "search_cases" in runs[right]:
            pair["actual_search_metrics"] = {metric: paired_bootstrap(runs[left]["search_cases"], runs[right]["search_cases"], metric, "search:" + left + "->" + right)
                for metric in ("recall_at_10", "ndcg_at_10")}
        pair["thematic_transfer_metrics"] = {
            metric: paired_bootstrap([row for row in aa if row["thematic_transfer"]],
                                     [row for row in bb if row["thematic_transfer"]], metric, "transfer:" + left + "->" + right)
            for metric in ("primary_success_conservative", "literal_atom_coverage", "recall_at_10", "ndcg_at_10")}
        comparisons.append(pair)
    report = {"schema_version": 1, "freeze": record(freeze_path),
        "sealed_sha256": frozen["sealed_sha256_from_public_manifest"],
        "final_questions": len(gold), "human_review": False, "independent_semantic_gold": False,
        "overall_semantic_acceptance_proven": False, "metric_definitions": definitions(),
        "run_labels_missing": sorted(set(frozen["configs"]) - set(runs)),
        "runs": runs, "predeclared_comparisons": comparisons,
        "no_tuning_authorized_by_results": True,
        "limits": ["Machine-labelled literal extraction, not broad independent semantic assessment",
                   "Unknown/refusal remains in primary denominator and is not success",
                   "Eight families with two dominant; cluster intervals cannot repair sampling bias",
                   "Thematic transfer only within this corpus, no second-organization quality proof",
                   "Latency comparisons require matched Teams/load and inspection of answer lengths"]}
    out.mkdir(parents=True)
    write(out / "metrics.json", report)
    for label, run in runs.items():
        write_rows(out / (label + "-cases.jsonl"), run["cases"])
    def formatted(value):
        return "unknown" if value is None else f"{value:.4f}"
    md = ["# Финальная автоматическая оценка зафиксированных конфигураций", "",
          "**Это literal/source диагностика; независимая смысловая правильность и полнота не установлены.** "
          "Неизвестные оценки и отказы не считаются успехом. Консервативный основной semantic показатель — 0; "
          "это нижняя граница при неизвестной разметке, а не доказанная нулевая фактическая точность.", "",
          "| Конфигурация | Полнота run | Literal atoms (macro) | Cited literal atoms | Recall@10 actual context | nDCG@10 actual context | Semantic unknown |",
          "|---|---:|---:|---:|---:|---:|---:|"]
    for label, run in runs.items():
        summary = run["overall"]
        rates = summary["rates"]
        md.append(f"| {label} | {summary['cases'] - summary['missing_results']}/{summary['cases']} | "
                  f"{formatted(rates['literal_atom_coverage'])} | {formatted(rates['cited_literal_atom_coverage'])} | "
                  f"{formatted(rates['recall_at_10'])} | {formatted(rates['ndcg_at_10'])} | {summary['cases']}/{summary['cases']} |")
    md += ["", "| Конфигурация | Первый непустой текст median/p95, с | Полный запрос median/p95, с | Длина ответа median, символы |",
           "|---|---:|---:|---:|"]
    for label, run in runs.items():
        summary = run["overall"]
        first, full, chars = (summary[key] for key in ("first_visible_seconds", "full_seconds", "answer_chars"))
        md.append(f"| {label} | {formatted(first['median'])}/{formatted(first['p95'])} | "
                  f"{formatted(full['median'])}/{formatted(full['p95'])} | {formatted(chars['median'])} |")
    md += ["", "Время включает конкретные startup/cache/load условия; первый случай отдельно указан в JSON. "
           "Короткий ответ, отказ или разная длина не доказывают ускорения вычислений. Реальный режим Teams "
           "и нагрузка должны совпадать с freeze и журналом запуска.", ""]
    for pair in comparisons:
        md.append(f"- {pair['left']} → {pair['right']}: {pair['effect']}; {pair['state']}.")
        if pair["state"] == "evaluated":
            interval = pair["metrics"]["literal_atom_coverage"]
            md.append(f"  Δ macro literal coverage={formatted(interval.get('difference_right_minus_left'))}; "
                      f"95% family CI={interval.get('ci95_family_bootstrap')}. "
                      f"Exact ordered contexts: {pair['identical_ordered_evidence_cases']}/{len(gold)}. "
                      "Смысловое преимущество не установлено.")
    md += ["", "Подробные RU/KZ, семейства, типы, held-out тематический transfer, неизвестные оценки, ошибки, "
           "пары и интервалы: [metrics.json](metrics.json). Восемь семейств с двумя доминирующими и "
           "document/phrase-assisted extraction ограничивают перенос выводов. Улучшения после просмотра "
           "этих ответов потребуют нового теста.", ""]
    (out / "summary.md").write_text("\n".join(md), encoding="utf-8")
    print(json.dumps({"out": str(out), "runs": {label: run["overall"] for label, run in runs.items()},
                      "semantic_acceptance_proven": False}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--out", type=Path, default=RESEARCH / "final-evaluation-preparation")
    freezing = commands.add_parser("freeze")
    freezing.add_argument("--spec", required=True, type=Path)
    freezing.add_argument("--out", required=True, type=Path)
    opening = commands.add_parser("open")
    opening.add_argument("--freeze", required=True, type=Path)
    opening.add_argument("--out", required=True, type=Path)
    scoring = commands.add_parser("score")
    scoring.add_argument("--freeze", required=True, type=Path)
    scoring.add_argument("--run", required=True, action="append")
    scoring.add_argument("--search-run", action="append")
    scoring.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.out.resolve())
    elif args.command == "freeze":
        freeze(args.spec.resolve(), args.out.resolve())
    elif args.command == "open":
        open_final(args.freeze.resolve(), args.out.resolve())
    else:
        score(args.freeze.resolve(), args.run, args.search_run, args.out.resolve())


if __name__ == "__main__":
    main()
