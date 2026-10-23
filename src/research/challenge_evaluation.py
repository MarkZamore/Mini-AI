"""Evaluate a frozen supplementary challenge without manufacturing semantic gold.

Literal overlap and forbidden-string matches are review candidates only. Accepted
automatic semantic judgements must bind to exact answers/cases and source quotes.
Full RAG and bounded-context component tests are always reported separately.
This module never reads the sealed final100 dataset or changes the corpus.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import statistics

import final_evaluation as common


ROOT = common.ROOT
DEFINITIONS = common.RESEARCH / "challenge-evaluation-preparation/metric-definitions-v1.json"
REVIEW_ORIGIN = "automatic_agent_source_review"
FACT_STATUSES = {"supported", "contradicted", "missing", "unknown"}
DENIAL = re.compile(r"не\s+(?:найден[оаы]?|содержится|подтвержден[оаы]?|указан[оаы]?)|"
                   r"нет\s+(?:подтверждения|утверждения|такого\s+правила)|"
                   r"неверно|неправда|does\s+not\s+(?:state|confirm)|not\s+found|"
                   r"көрсетілмеген|расталмаған|дұрыс\s+емес", re.IGNORECASE)


def text_sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def case_sha(case):
    value = {key: content for key, content in case.items() if key != "case_sha256"}
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
    return text_sha(raw)


def safe_path(path):
    path = common.absolute(path)
    if any(part.casefold() == "sealed-final" for part in path.parts):
        raise ValueError("Challenge evaluation must not read sealed final100")
    return path


def all_records(value):
    if isinstance(value, dict):
        if {"path", "bytes", "sha256"} <= value.keys():
            yield value
        for nested in value.values():
            yield from all_records(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from all_records(nested)


def frozen_record(path, frozen):
    path = safe_path(path)
    records = [record for record in all_records(frozen) if Path(record["path"]).resolve() == path]
    actual = common.record(path)
    if not records or not any(record["sha256"] == actual["sha256"] for record in records):
        raise ValueError(f"Challenge artifact was not included in the pre-answer freeze: {path}")
    return actual


def criteria(case):
    if case["required_facts"]:
        return case["required_facts"]
    if case["expected_mode"] == "clarify":
        return [{"id": value.get("id", f"clarification-{index + 1}") if isinstance(value, dict) else f"clarification-{index + 1}",
                 "text": value["text"] if isinstance(value, dict) else value,
                 "evidence_ids": [], "kind": "clarification", "meta_behaviour": True}
                for index, value in enumerate(case.get("required_clarifications", []))]
    return []


def load_cases(path):
    rows = common.read_rows(safe_path(path))
    ids = set()
    for row in rows:
        required = {"id", "question", "language", "category", "family_ids", "intent_group", "scope",
                    "source_ids", "required_facts", "forbidden_assertions", "expected_mode", "review_status", "limitations"}
        if not required <= row.keys() or row["id"] in ids:
            raise ValueError("Missing challenge schema fields or duplicate case ID")
        ids.add(row["id"])
        if row.get("case_sha256") and row["case_sha256"] != case_sha(row):
            raise ValueError("Challenge case digest differs from its frozen content")
        if row["scope"] not in {"full_rag", "bounded_context"} or row["expected_mode"] not in {"answer", "clarify", "insufficient"}:
            raise ValueError("Unknown challenge scope or expected response mode")
        if row["expected_mode"] == "answer" and (not row["family_ids"] or not row["required_facts"]):
            raise ValueError("Answerable cases need actual source families and a mandatory fact rubric")
        if row["expected_mode"] == "clarify" and not criteria(row):
            raise ValueError("Clarification cases need explicit clarification criteria")
        if row["expected_mode"] == "insufficient" and row["scope"] != "bounded_context":
            raise ValueError("This challenge cannot infer corpus-wide absence from a source subset")
        if row["scope"] == "bounded_context" and not isinstance(row.get("context_ids"), list):
            raise ValueError("Bounded context requires its explicit fixed evidence IDs")
        fact_ids = []
        for fact in row["required_facts"]:
            if not {"id", "text", "evidence_ids", "kind"} <= fact.keys() or fact["kind"] not in {"fact", "condition", "exception"}:
                raise ValueError("Malformed mandatory fact/condition/exception rubric")
            if not set(fact["evidence_ids"]) <= set(row["source_ids"]):
                raise ValueError("Gold fact evidence outside the predeclared case sources")
            fact_ids.append(fact["id"])
        if len(fact_ids) != len(set(fact_ids)):
            raise ValueError("Duplicate mandatory fact ID")
        forbidden_ids = [assertion["id"] for assertion in row["forbidden_assertions"]]
        if len(forbidden_ids) != len(set(forbidden_ids)):
            raise ValueError("Duplicate forbidden assertion ID")
    return rows


def source(database, fragment_id):
    row = database.execute("SELECT fragment_id,document_id,text,source_sha256,source_refs FROM chunks WHERE fragment_id=?", (fragment_id,)).fetchone()
    if row is None:
        raise ValueError(f"Challenge evidence absent from frozen canonical corpus: {fragment_id}")
    return {"fragment_id": row[0], "document_id": row[1], "text": row[2],
            "source_sha256": row[3], "source_refs": json.loads(row[4])}


def check_gold(cases, database):
    for case in cases:
        for fragment_id in case["source_ids"]:
            source(database, fragment_id)
        for fact in case["required_facts"]:
            for quote in fact.get("support_quotes", []):
                fragment_id = quote["fragment_id"]
                if fragment_id not in fact["evidence_ids"] or common.norm(quote["text"]) not in common.norm(source(database, fragment_id)["text"]):
                    raise ValueError("Mandatory gold quote does not match its frozen canonical evidence")


def run_paths(items, frozen):
    result = {}
    for item in items:
        if "=" not in item:
            raise ValueError("Use VARIANT=answers.jsonl")
        label, filename = item.split("=", 1)
        if label not in frozen["configs"]:
            raise ValueError("Unknown frozen configuration")
        path = safe_path(filename)
        if path in result.get(label, []):
            raise ValueError("Repeated answer artifact")
        result.setdefault(label, []).append(path)
    return result


def extract(raw, case):
    if raw is None:
        return {"answer": "", "sources": [], "missing": True, "success": False, "events": [],
                "first_visible_seconds": None, "full_seconds": None}
    if raw.get("question") != case["question"]:
        raise ValueError("Challenge runner changed the frozen question")
    if set(raw.get("modules", [])) != common.MODULES or len(raw["modules"]) != 7:
        raise ValueError("Only the complete seven-module composition is allowed")
    if raw.get("scope", case["scope"]) != case["scope"]:
        raise ValueError("Runner scope differs from the frozen challenge case")
    history = [message for message in raw.get("history", []) if message.get("role") == "assistant"]
    if not history:
        raise ValueError("Actual assistant history and actual source inventory are required; no gold-context substitution")
    message = history[-1]
    if not isinstance(message.get("text"), str) or not isinstance(message.get("sources"), list):
        raise ValueError("Malformed actual final visible answer/source inventory")
    events = raw.get("events", [])
    first = next((event.get("atSeconds") for event in events if event.get("type") in {"token", "done"} and str(event.get("text", "")).strip()), None)
    recorded = raw.get("firstNonemptyTextSeconds")
    if common.finite(first) and common.finite(recorded) and abs(first - recorded) > .001:
        raise ValueError("First visible text timing differs from actual nonempty token/done event")
    errors = [event.get("error") for event in events if event.get("error")]
    result = raw.get("result", {})
    if result.get("error"):
        errors.append(result["error"])
    return {"answer": message["text"], "sources": message["sources"], "missing": False,
            "success": result.get("success") is True and not errors,
            "events": events, "first_visible_seconds": first, "full_seconds": raw.get("fullSeconds"),
            "errors": errors}


def answer_inventory(paths, cases):
    known = {case["id"] for case in cases}
    result = {}
    for path in paths:
        for row in common.read_rows(path):
            if row.get("id") not in known or row["id"] in result:
                raise ValueError("Unknown or repeated challenge response ID")
            result[row["id"]] = row
    return result


def review_template(case, answer, label):
    return {"id": case["id"], "variant": label, "case_sha256": case_sha(case),
            "answer_sha256": text_sha(answer["answer"]), "review_origin": REVIEW_ORIGIN,
            "reviewer": "UNASSIGNED", "human_review": False, "review_complete": False,
            "facts": [{"fact_id": fact["id"], "status": "unknown", "claim_quote": "",
                       "stance": "ambiguous", "evidence_quotes": [], "rationale": ""}
                      for fact in criteria(case)],
            "mode": {"judgement": "unknown", "observed": "unknown", "rationale": ""},
            "critical_review_status": "unknown", "critical_errors": [],
            "forbidden_assertion_reviews": [{"id": assertion["id"], "judgement": "unknown", "rationale": ""}
                                            for assertion in case["forbidden_assertions"]],
            "unreviewed_additional_assertions": "unknown", "limitations": [
                "Automatic agent/source assessment, not independent human gold",
                "Do not count a quotation inside a denial as an affirmed rule",
                "For bounded context, do not infer corpus-wide absence"]}


def load_reviews(paths):
    result = {}
    artifacts = []
    for path in paths or []:
        path = safe_path(path)
        artifacts.append(common.record(path))
        for row in common.read_rows(path):
            key = (row["variant"], row["id"])
            if key in result:
                raise ValueError("Duplicate semantic review for the same variant and case")
            result[key] = row
    return result, artifacts


def validate_quote(quote, allowed_ids, database):
    if not {"fragment_id", "text"} <= quote.keys() or not quote["text"]:
        raise ValueError("Semantic review requires an exact nonempty source quote")
    if quote["fragment_id"] not in allowed_ids:
        raise ValueError("Semantic review evidence is outside the predeclared case source scope")
    if common.norm(quote["text"]) not in common.norm(source(database, quote["fragment_id"])["text"]):
        raise ValueError("Semantic review source quote is not in the canonical fragment")


def denial_near(text, phrase):
    position = common.norm(text).find(common.norm(phrase)) if phrase else -1
    if position < 0:
        return False
    return bool(DENIAL.search(common.norm(text)[max(0, position - 180):position]))


def assess(case, answer, review, database):
    text = answer["answer"]
    content = text.partition("\n\nОграничения источников:")[0]
    citations = common.MARKER.findall(text)
    ids = [value.get("fragment_id") for value in answer["sources"]]
    bad_sources = common.context_check(answer["sources"], database)
    bad_citations = [citation for citation in citations if citation not in ids]
    bounded = case["scope"] == "bounded_context"
    outside_bounded = [citation for citation in citations if citation not in case.get("context_ids", [])] if bounded else []
    facts = []
    for fact in criteria(case):
        facts.append({"fact_id": fact["id"], "kind": fact["kind"], "critical": bool(fact.get("critical")),
            "literal_overlap": common.norm(fact["text"]) in common.norm(content),
            "denial_near_literal_overlap": denial_near(content, fact["text"]),
            "known_evidence_retrieved": bool(set(fact["evidence_ids"]) & set(ids)),
            "known_evidence_cited": bool(set(fact["evidence_ids"]) & set(citations)),
            "automatic_semantic_status": "unknown", "review_supplied": False})
    candidates = []
    for assertion in case["forbidden_assertions"]:
        present = bool(assertion.get("text") and common.norm(assertion["text"]) in common.norm(content))
        candidates.append({"id": assertion["id"], "literal_candidate": present,
                           "denial_near_candidate": denial_near(content, assertion.get("text", "")),
                           "automatically_proved_error": False, "review_judgement": "unknown"})
    mode = {"judgement": "unknown", "observed": "unknown"}
    semantic_complete = False
    critical_status = "unknown"
    confirmed_critical = []
    reviewer = None
    if review is not None:
        if review.get("case_sha256") != case_sha(case) or review.get("answer_sha256") != text_sha(text):
            raise ValueError("Semantic review does not bind to this exact frozen case and actual visible answer")
        if review.get("review_origin") != REVIEW_ORIGIN or review.get("human_review") is not False:
            raise ValueError("Review must be labelled automatic agent/source review, never independent human review")
        reviewer = review.get("reviewer")
        if not reviewer or reviewer == "UNASSIGNED":
            raise ValueError("Review origin/reviewer must be recorded")
        review_facts = {value["fact_id"]: value for value in review.get("facts", [])}
        if len(review_facts) != len(review.get("facts", [])) or set(review_facts) != {fact["id"] for fact in criteria(case)}:
            raise ValueError("Semantic review must account for every mandatory fact exactly once")
        for gold, fact in zip(criteria(case), facts):
            value = review_facts[gold["id"]]
            status = value.get("status")
            if status not in FACT_STATUSES:
                raise ValueError("Unknown fact judgement")
            claim = value.get("claim_quote", "")
            if status in {"supported", "contradicted"}:
                if not claim or claim not in text or not value.get("rationale") or (not gold.get("meta_behaviour") and not value.get("evidence_quotes")):
                    raise ValueError("Asserted fact judgement requires actual claim, source quotes and explanation")
                for quote in value["evidence_quotes"]:
                    validate_quote(quote, gold["evidence_ids"], database)
                if status == "supported" and value.get("stance") != "affirmed":
                    status = "unknown"
                if status == "supported" and denial_near(content, claim) and not value.get("negation_disambiguation"):
                    status = "unknown"
                if status == "supported" and case["expected_mode"] == "answer" and not (
                        {quote["fragment_id"] for quote in value["evidence_quotes"]} & set(citations)):
                    status = "unknown"
            fact["automatic_semantic_status"] = status
            fact["review_supplied"] = True
            if status == "contradicted" and gold.get("critical"):
                confirmed_critical.append({"kind": "critical_mandatory_fact_contradiction", "fact_id": gold["id"]})
        mode = review.get("mode", {})
        if mode.get("judgement") not in {"correct", "incorrect", "unknown"}:
            raise ValueError("Unknown response-mode review")
        if mode.get("judgement") != "unknown" and not mode.get("rationale"):
            raise ValueError("Response mode judgement requires explanation")
        if mode.get("judgement") == "correct" and mode.get("observed") != case["expected_mode"]:
            raise ValueError("Correct mode must match the explicitly frozen expected mode")
        critical_status = review.get("critical_review_status", "unknown")
        if critical_status not in {"unknown", "none_found_in_source_checked_answer", "confirmed_error"}:
            raise ValueError("Unknown critical-error review status")
        for error in review.get("critical_errors", []):
            if error.get("status") != "confirmed":
                continue
            if error.get("kind") not in {"actor", "deadline", "action", "exception", "applicability", "source"}:
                raise ValueError("Unknown critical-error category")
            if not error.get("claim_quote") or error["claim_quote"] not in text or not error.get("rationale") or not error.get("evidence_quotes"):
                raise ValueError("Confirmed critical error needs an actual assertion and original-source proof")
            for quote in error["evidence_quotes"]:
                validate_quote(quote, case["source_ids"], database)
            confirmed_critical.append(error)
        forbidden_reviews = {value["id"]: value for value in review.get("forbidden_assertion_reviews", [])}
        if len(forbidden_reviews) != len(review.get("forbidden_assertion_reviews", [])):
            raise ValueError("Duplicate forbidden-assertion review")
        if set(forbidden_reviews) - {candidate["id"] for candidate in candidates}:
            raise ValueError("Foreign forbidden-assertion review ID")
        for candidate in candidates:
            value = forbidden_reviews.get(candidate["id"], {})
            judgement = value.get("judgement", "unknown")
            if judgement not in {"asserted_error", "not_asserted", "unknown"}:
                raise ValueError("Unknown forbidden-assertion judgement")
            if judgement != "unknown" and not value.get("rationale"):
                raise ValueError("Forbidden-assertion decision requires an explanation")
            candidate["review_judgement"] = judgement
            if judgement == "asserted_error" and not confirmed_critical:
                raise ValueError("Asserted forbidden error requires a confirmed critical error with source proof")
        if confirmed_critical and critical_status == "none_found_in_source_checked_answer":
            raise ValueError("Contradictory critical-error review")
        if critical_status == "confirmed_error" and not confirmed_critical:
            raise ValueError("Confirmed critical-error status requires an actual assertion and source proof")
        semantic_complete = bool(review.get("review_complete") is True
            and review.get("unreviewed_additional_assertions") == "no"
            and all(fact["automatic_semantic_status"] != "unknown" for fact in facts)
            and all(not candidate["literal_candidate"] or candidate["review_judgement"] != "unknown" for candidate in candidates)
            and mode.get("judgement") != "unknown" and critical_status != "unknown"
            and (case["review_status"] == "automatic_source_verified"
                 or (case["expected_mode"] == "clarify" and case["review_status"] in
                     {"not_applicable_meta_clarification", "automatic_question_scope_verified"})))
    mechanical_critical = bool(bad_sources or bad_citations or outside_bounded)
    supported = sum(fact["automatic_semantic_status"] == "supported" for fact in facts)
    ratio = supported / len(facts) if facts else None
    answerable = case["expected_mode"] == "answer"
    accepted = bool(answer["success"] and not answer["missing"] and semantic_complete and (ratio is None or ratio >= .9)
                    and mode.get("judgement") == "correct" and critical_status == "none_found_in_source_checked_answer"
                    and not confirmed_critical and not mechanical_critical
                    and (not answerable or bool(citations)))
    return {"id": case["id"], "case_sha256": case_sha(case), "answer_sha256": text_sha(text),
        "language": case["language"], "category": case["category"], "family_ids": case["family_ids"],
        "intent_group": case["intent_group"], "scope": case["scope"], "expected_mode": case["expected_mode"],
        "answerable_primary_case": answerable, "request_success": answer["success"], "missing_result": answer["missing"],
        "answer_chars": len(text), "facts": facts, "forbidden_candidates": candidates,
        "citations": citations, "invalid_citations": bad_citations, "invalid_sources": bad_sources,
        "actual_context_ids": ids, "ordered_context_sha256": common.value_sha(answer["sources"]),
        "extra_retrieved_bounded_context_ids": [value for value in ids if value not in case.get("context_ids", [])] if bounded else [],
        "outside_bounded_citations": outside_bounded, "mechanical_critical_error": mechanical_critical,
        "literal_overlap_rate": sum(fact["literal_overlap"] for fact in facts) / len(facts) if facts else None,
        "literal_overlap_is_semantic_pass": False,
        "automatic_supported_fact_coverage_conservative": ratio,
        "automatic_semantic_review_complete": semantic_complete,
        "automatic_semantic_review_origin": REVIEW_ORIGIN if review else None, "reviewer": reviewer,
        "independent_human_semantic_review": False, "mode_review": mode,
        "critical_review_status": critical_status, "confirmed_critical_errors": confirmed_critical,
        "automatic_review_success_conservative": float(accepted),
        "primary_answer_success_conservative": float(accepted) if answerable else None,
        "semantic_status_unknown": not semantic_complete,
        "first_visible_seconds": answer["first_visible_seconds"], "full_seconds": answer["full_seconds"],
        "evaluation_limit": "Bounded supplied context only, no corpus-wide absence claim" if bounded else "Full-RAG supplementary Codex-authored challenge; not historically blind"}


def summary(rows):
    primary = [row for row in rows if row["answerable_primary_case"]]
    known = [row for row in rows if row["automatic_semantic_review_complete"]]
    def rate(items, field):
        values = [float(row[field]) for row in items if row[field] is not None]
        return sum(values) / len(values) if values else None
    return {"cases": len(rows), "answerable_cases": len(primary),
            "primary_answer_success_conservative": rate(primary, "primary_answer_success_conservative"),
            "all_mode_automatic_review_success_conservative": rate(rows, "automatic_review_success_conservative"),
            "automatic_review_known_cases": len(known), "unknown_cases": len(rows) - len(known),
            "automatic_review_coverage": len(known) / len(rows) if rows else None,
            "accuracy_among_known_automatic_reviews": rate(known, "automatic_review_success_conservative"),
            "missing_results": sum(row["missing_result"] for row in rows),
            "confirmed_critical_error_cases": sum(bool(row["confirmed_critical_errors"]) for row in rows),
            "mechanical_critical_error_cases": sum(row["mechanical_critical_error"] for row in rows),
            "literal_overlap_macro_proxy": rate(rows, "literal_overlap_rate"),
            "automatic_supported_fact_coverage_conservative": rate(rows, "automatic_supported_fact_coverage_conservative"),
            "mandatory_fact_metric_cases": sum(row["automatic_supported_fact_coverage_conservative"] is not None for row in rows),
            "language_counts": dict(Counter(row["language"] for row in rows)),
            "category_counts": dict(Counter(row["category"] for row in rows)),
            "family_counts": dict(Counter(family for row in rows for family in row["family_ids"])),
            "first_visible_seconds": common.distribution([row["first_visible_seconds"] for row in rows]),
            "full_seconds": common.distribution([row["full_seconds"] for row in rows]),
            "answer_chars": common.distribution([row["answer_chars"] for row in rows]),
            "independent_human_review": False}


def evaluate(args, templates=False):
    out = args.out.resolve()
    if out.exists():
        raise FileExistsError("Preserve previous challenge results; choose a fresh output directory")
    frozen = common.validate_freeze(args.freeze)
    artifacts = {"dataset": frozen_record(args.dataset, frozen), "definitions": frozen_record(DEFINITIONS, frozen),
                 "evaluator": frozen_record(__file__, frozen), "source_verification": frozen_record(args.source_verification, frozen)}
    cases = load_cases(args.dataset)
    answers = run_paths(args.run, frozen)
    review_rows, review_artifacts = load_reviews(getattr(args, "reviews", None))
    valid_keys = {(label, case["id"]) for label in answers for case in cases}
    if set(review_rows) - valid_keys:
        raise ValueError("Review contains a foreign variant/case")
    db = sqlite3.connect(Path(frozen["corpus_database"]["path"]).as_uri() + "?mode=ro&immutable=1", uri=True)
    runs = {}
    generated_templates = []
    try:
        check_gold(cases, db)
        for label, paths in answers.items():
            raw = answer_inventory(paths, cases)
            extracted = {case["id"]: extract(raw.get(case["id"]), case) for case in cases}
            if templates:
                generated_templates.extend(review_template(case, extracted[case["id"]], label) for case in cases)
                continue
            rows = [assess(case, extracted[case["id"]], review_rows.get((label, case["id"])), db) for case in cases]
            runs[label] = {"answers": [common.record(path) for path in paths], "cases": rows,
                          "full_rag": summary([row for row in rows if row["scope"] == "full_rag"]),
                          "bounded_context": summary([row for row in rows if row["scope"] == "bounded_context"]),
                          "categories": {category: summary([row for row in rows if row["category"] == category])
                                         for category in sorted({row["category"] for row in rows})}}
    finally:
        db.close()
    out.mkdir(parents=True)
    if templates:
        common.write_rows(out / "automatic-review-template.jsonl", generated_templates)
        common.write(out / "manifest.json", {"freeze": common.record(args.freeze), "artifacts": artifacts,
            "answers": {label: [common.record(path) for path in paths] for label, paths in answers.items()},
            "review_origin_required": REVIEW_ORIGIN, "human_review": False, "sealed_final100_read": False})
    else:
        comparisons = []
        for pair in frozen["comparisons"]:
            left, right = pair["left"], pair["right"]
            if left not in runs or right not in runs:
                continue
            for scope in ("full_rag", "bounded_context"):
                aa = [row for row in runs[left]["cases"] if row["scope"] == scope]
                bb = [row for row in runs[right]["cases"] if row["scope"] == scope]
                same_context = sum(not a["missing_result"] and not b["missing_result"] and a["ordered_context_sha256"] == b["ordered_context_sha256"] for a, b in zip(aa, bb))
                comparisons.append({**pair, "scope": scope, "cases": len(aa), "same_actual_ordered_context_cases": same_context,
                    "unanchored_mode_cases_excluded_from_source_cluster_CI": sum(not row["family_ids"] for row in aa),
                    "context_control_satisfied": same_context == len(aa) if pair["context_equality_required"] else None,
                    "metrics": {metric: common.paired_bootstrap([row for row in aa if row["family_ids"]],
                                                               [row for row in bb if row["family_ids"]], metric, "challenge:" + left + "->" + right + ":" + scope)
                                for metric in ("primary_answer_success_conservative", "automatic_review_success_conservative", "literal_overlap_rate")}})
        common.write(out / "metrics.json", {"schema_version": 1, "freeze": common.record(args.freeze), "artifacts": artifacts,
            "reviews": review_artifacts, "runs": runs, "comparisons": comparisons,
            "metric_definitions": common.load(DEFINITIONS), "sealed_final100_read": False, "human_review": False,
            "challenge_historically_blind": False, "full_rag_and_bounded_context_are_separate": True,
            "no_corpus_wide_absence_inference": True})
        for label, run in runs.items():
            common.write_rows(out / (label + "-cases.jsonl"), run["cases"])
    print(json.dumps({"out": str(out), "variants": list(answers), "cases": len(cases),
                      "templates_only": templates, "sealed_final100_read": False}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("templates", "score"):
        sub = commands.add_parser(command)
        sub.add_argument("--freeze", required=True, type=Path)
        sub.add_argument("--dataset", required=True, type=Path)
        sub.add_argument("--source-verification", required=True, type=Path)
        sub.add_argument("--run", required=True, action="append")
        sub.add_argument("--out", required=True, type=Path)
        if command == "score":
            sub.add_argument("--reviews", type=Path, action="append")
    args = parser.parse_args()
    evaluate(args, templates=args.command == "templates")


if __name__ == "__main__":
    main()
