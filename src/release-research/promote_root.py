"""Deploy an already frozen runtime locally, retaining every replaced unit.

The canonical docs/db are intentionally excluded: their deployment has a separate
inventory and journal. No file is deleted. SHA verification precedes promotion.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
from datetime import datetime, timezone

import psutil


UNITS = ("Mini-AI.exe", "vcruntime140.dll", "vcruntime140_1.dll", "native", "licenses", "model")
REPLACEMENT_FILE_COUNT = 335
FALLBACK_CONTROL_KEY = "untrained"


def native(path: Path) -> Path:
    value = str(path.absolute())
    return Path("\\\\?\\" + value) if os.name == "nt" and not value.startswith("\\\\?\\") else path


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with native(path).open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def load(path: Path):
    return json.loads(native(path).read_text(encoding="utf-8-sig"))


def contained(path: Path, root: Path) -> None:
    path.absolute().relative_to(root.absolute())
    current = path.absolute()
    while True:
        if native(current).exists():
            info = native(current).lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                raise ValueError(f"Reparse/symlink path forbidden: {current}")
        if current == root.absolute():
            break
        current = current.parent


def normalized(path: Path) -> Path:
    return Path(os.path.abspath(path))


def validate_locations(root: Path, runtime: Path, output: Path, stage: Path, freeze: Path) -> None:
    for path in (runtime, output, stage, freeze):
        contained(path, root)
        for unit in UNITS:
            protected = root / unit
            if path == protected or protected in path.parents:
                raise ValueError(f"Deployment input/output cannot be inside a replacement unit: {path}")
    if output == runtime or runtime in output.parents or output in runtime.parents:
        raise ValueError("Deployment output and frozen runtime must not overlap")


def frozen_replacement_rows(stage: dict, freeze: dict, runtime: Path, role: str = "final") -> list[dict]:
    selected = freeze.get("configs", {}).get(role)
    if not isinstance(selected, dict) or normalized(Path(selected.get("runtime_root", ""))) != runtime:
        raise ValueError(f"Runtime differs from the explicitly selected frozen configuration: {role}")
    if normalized(Path(stage.get("root", ""))) != runtime:
        raise ValueError("Stage root differs from the selected runtime")
    if stage.get("file_count") != len(stage["files"]) or stage["file_count"] != selected.get("expected_full_runtime_files"):
        raise ValueError("Full staged file count differs from the frozen configuration")
    rows = [row for row in stage["files"] if row["path"].split("/")[0] in UNITS]
    if {row["path"].split("/")[0] for row in rows} != set(UNITS):
        raise ValueError("Incomplete set of replacement units")

    def index(entries, absolute: bool) -> dict:
        result = {}
        folded = set()
        for row in entries:
            relative = normalized(Path(row["path"])).relative_to(runtime) if absolute else Path(row["path"])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Unsafe inventory path")
            if relative.parts[0] not in UNITS:
                continue
            name = relative.as_posix()
            if name.casefold() in folded:
                raise ValueError(f"Duplicate replacement path: {name}")
            folded.add(name.casefold())
            result[name] = (row["bytes"], row["sha256"])
        return result

    staged = index(rows, False)
    frozen = index(selected["inventory"], True)
    if staged != frozen:
        raise ValueError("Replacement file paths, sizes or SHA differ from the selected frozen inventory")
    if len(staged) != REPLACEMENT_FILE_COUNT:
        raise ValueError(f"Exactly {REPLACEMENT_FILE_COUNT} frozen replacement files required")
    if staged["model/worker.json"][1] != selected.get("model_manifest_sha256"):
        raise ValueError("Frozen worker manifest metadata differs from its inventory")
    return rows


def checked_reference(record: dict, root: Path) -> Path:
    """Verify a decision-linked file without opening its semantic evidence."""
    if (not isinstance(record, dict) or not isinstance(record.get("path"), str)
            or not Path(record["path"]).is_absolute()
            or type(record.get("bytes")) is not int or record["bytes"] <= 0
            or not isinstance(record.get("sha256"), str) or len(record["sha256"]) != 64
            or any(character not in "0123456789abcdef" for character in record["sha256"])):
        raise ValueError("Decision reference requires an absolute path, positive size and SHA-256")
    path = normalized(Path(record["path"]))
    contained(path, root)
    if native(path).stat().st_size != record["bytes"] or digest(path) != record["sha256"]:
        raise ValueError(f"Decision-linked file size/SHA mismatch: {path}")
    return path


def selection(root: Path, runtime: Path, output: Path, stage_path: Path,
              freeze_path: Path | None, decision_path: Path | None) -> tuple[dict, dict, str, Path, dict]:
    """Resolve an original final freeze or a separately recorded fallback decision.

    A decision never manufactures or rewrites a freeze. Its selected runtime must
    already exist as the named matched control in the original, hash-linked freeze.
    Evidence/result files referenced by the decision are not opened here.
    """
    authority_path = decision_path if decision_path is not None else freeze_path
    if authority_path is None or (freeze_path is not None and decision_path is not None):
        raise ValueError("Exactly one deployment authority is required")
    validate_locations(root, runtime, output, stage_path, authority_path)
    stage = load(stage_path)
    if stage.get("state") != "full-runtime-staged":
        raise ValueError("Actual full staged runtime required")
    authority_sha = digest(authority_path)
    metadata = {"authority_path": str(authority_path), "authority_sha256": authority_sha,
                "stage_manifest_sha256": digest(stage_path)}
    if decision_path is None:
        freeze = load(authority_path)
        role = "final"
        expected = freeze
        configuration_id = freeze.get("configuration_id")
        linked_freeze_path = authority_path
        metadata["deployment_authority"] = "original-final-freeze"
    else:
        decision = load(decision_path)
        if (decision.get("schema_version") != 2 or decision.get("state") != "operational-fallback"
                or decision.get("role") != "operational-fallback"):
            raise ValueError("Only an explicit operational-fallback decision is accepted")
        role = decision.get("evaluated_runtime_key")
        if role != FALLBACK_CONTROL_KEY:
            raise ValueError("Fallback must explicitly select the previously frozen untrained control")
        if (decision.get("production_source_prompt_thresholds_changed") is not False
                or decision.get("new_weights_or_hyperparameters_selected") is not False):
            raise ValueError("Fallback cannot authorize new production settings, weights or hyperparameters")
        if normalized(Path(decision.get("runtime_root", ""))) != runtime:
            raise ValueError("Decision runtime differs from the requested control runtime")
        linked = decision.get("evaluation_freeze", {})
        linked_freeze_path = checked_reference(linked, root)
        validate_locations(root, runtime, output, stage_path, linked_freeze_path)
        if linked_freeze_path == decision_path:
            raise ValueError("Decision cannot be its own original freeze")
        stage_reference = decision.get("runtime_inventory", {})
        if checked_reference(stage_reference, root) != stage_path:
            raise ValueError("Decision inventory differs from the requested stage manifest")
        coverage_reference = decision.get("coverage_audit", {})
        coverage_path = checked_reference(coverage_reference, root)
        validate_locations(root, runtime, output, stage_path, coverage_path)
        freeze = load(linked_freeze_path)
        expected = decision
        configuration_id = decision.get("configuration_id")
        selected = freeze.get("configs", {}).get(role)
        if (not isinstance(selected, dict) or selected.get("role") != "matched_baseline"
                or normalized(Path(selected.get("runtime_root", ""))) != runtime):
            raise ValueError("Decision does not select the original frozen matched control")
        if decision.get("model_manifest_sha256") != selected.get("model_manifest_sha256"):
            raise ValueError("Decision manifest differs from the previously frozen control manifest")
        for key in ("app_sha256", "corpus_sha256"):
            if decision.get(key) != freeze.get(key):
                raise ValueError(f"Decision changes the original frozen {key}")
        metadata.update(deployment_authority="operational-fallback", decision_path=str(decision_path),
                        decision_sha256=authority_sha, original_freeze_sha256=linked["sha256"],
                        coverage_audit_path=str(coverage_path), coverage_audit_sha256=coverage_reference["sha256"])
    if freeze.get("state") != "final-frozen":
        raise ValueError("Original final evaluation freeze required")
    if not isinstance(configuration_id, str) or not configuration_id.strip():
        raise ValueError("Deployment configuration ID is required")
    for key in ("app_sha256", "model_manifest_sha256", "corpus_sha256"):
        if stage.get(key) != expected.get(key):
            raise ValueError(f"Stage/deployment authority mismatch: {key}")
    metadata.update(configuration_id=configuration_id, frozen_runtime_key=role,
                    original_freeze_path=str(linked_freeze_path),
                    original_freeze_sha256=digest(linked_freeze_path))
    return stage, freeze, role, linked_freeze_path, metadata


def record(journal: Path, event: str, **values) -> None:
    with native(journal).open("a", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps({"utc": datetime.now(timezone.utc).isoformat(), "event": event, **values}, ensure_ascii=False) + "\n")
        output.flush()
        os.fsync(output.fileno())


def assert_idle(root: Path) -> None:
    running = []
    for process in psutil.process_iter(["pid", "name", "exe"]):
        try:
            name = (process.info["name"] or "").lower()
            if name in ("mini-ai.exe", "enterpriseai.exe", "inference-worker.exe"):
                running.append({key: process.info[key] for key in ("pid", "name", "exe")})
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    if running:
        raise RuntimeError(f"Application/worker processes must finish first: {running}")
    if native(root / "src/build/research-20261024/gpu.lock").exists():
        raise RuntimeError("GPU lease still exists; coordinate its owner before deployment")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--stage-manifest", type=Path, required=True)
    authority = parser.add_mutually_exclusive_group(required=True)
    authority.add_argument("--freeze", type=Path, help="Original final freeze; selects its final configuration")
    authority.add_argument("--decision", type=Path, help="Operational-fallback decision linked to the original freeze")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root, runtime, output, stage_path = (
        normalized(value) for value in (args.root, args.runtime, args.output, args.stage_manifest))
    freeze_path = normalized(args.freeze) if args.freeze is not None else None
    decision_path = normalized(args.decision) if args.decision is not None else None
    if output == root or native(output).exists():
        raise ValueError("Output must be a new, versioned directory inside this workspace")
    stage, freeze, role, original_freeze_path, authority_metadata = selection(
        root, runtime, output, stage_path, freeze_path, decision_path)
    rows = frozen_replacement_rows(stage, freeze, runtime, role)
    for row in rows:
        relative = Path(row["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Unsafe inventory path")
        contained(runtime / relative, root)
    for unit in UNITS:
        contained(root / unit, root)
    instructions = root / "model/instructions.md"
    if native(instructions).exists():
        contained(instructions, root)
        if any(row["path"] == "model/instructions.md" for row in rows):
            raise ValueError("Preserved user instructions conflict with inventory")
    plan = {**authority_metadata, "root": str(root), "runtime": str(runtime),
            "output": str(output), "replacement_units": list(UNITS), "files": len(rows),
            "logical_bytes": sum(row["bytes"] for row in rows), "preserve_user_instructions": native(instructions).exists(),
            "corpus_action": "verify existing SQLite only; no docs/db mutation", "apply": args.apply}
    if not args.apply:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return
    assert_idle(root)
    if shutil.disk_usage(root).free < 20 * 1024**3:
        raise RuntimeError("Keep at least 20 GiB free on the workspace volume")
    # Verify everything before creating a replacement or moving an old file.
    for row in rows:
        source = runtime / row["path"]
        if native(source).stat().st_size != row["bytes"] or digest(source) != row["sha256"]:
            raise ValueError(f"Payload mismatch: {source}")
    if digest(root / "db/index.sqlite") != stage["corpus_sha256"]:
        raise ValueError("Root canonical SQLite differs from the frozen corpus")
    if digest(Path(authority_metadata["authority_path"])) != authority_metadata["authority_sha256"]:
        raise ValueError("Deployment authority changed during source verification")
    if digest(original_freeze_path) != authority_metadata["original_freeze_sha256"]:
        raise ValueError("Original evaluation freeze changed during source verification")
    if digest(stage_path) != authority_metadata["stage_manifest_sha256"]:
        raise ValueError("Stage manifest changed during source verification")
    if ("coverage_audit_path" in authority_metadata
            and digest(Path(authority_metadata["coverage_audit_path"])) != authority_metadata["coverage_audit_sha256"]):
        raise ValueError("Decision-linked coverage audit changed during source verification")
    native(output).mkdir(parents=True, exist_ok=False)
    incoming, backup = output / "incoming", output / "backup"
    native(incoming).mkdir()
    native(backup).mkdir()
    journal = output / "journal.jsonl"
    record(journal, "verified", **plan, freeze_sha256=digest(original_freeze_path), stage_sha256=digest(stage_path))
    for row in rows:
        source, target = runtime / row["path"], incoming / row["path"]
        native(target.parent).mkdir(parents=True, exist_ok=True)
        # worker.json is mutable at runtime; do not share its inode with frozen input.
        if row["path"] == "model/worker.json":
            shutil.copyfile(native(source), native(target))
        else:
            os.link(native(source), native(target))
    if native(instructions).exists():
        shutil.copyfile(native(instructions), native(incoming / "model/instructions.md"))
        record(journal, "instructions_preserved", sha256=digest(instructions))
    assert_idle(root)
    moved_old, moved_new = [], []
    try:
        for unit in UNITS:
            current, saved, replacement = root / unit, backup / unit, incoming / unit
            if native(current).exists():
                os.rename(native(current), native(saved))
                moved_old.append(unit)
                record(journal, "old_unit_saved", unit=unit)
            os.rename(native(replacement), native(current))
            moved_new.append(unit)
            record(journal, "new_unit_promoted", unit=unit)
        for row in rows:
            # NTFS hardlinks retain verified contents; separately copied JSON is checked too.
            if native(root / row["path"]).stat().st_size != row["bytes"]:
                raise ValueError(f"Promoted size mismatch: {row['path']}")
        if digest(root / "Mini-AI.exe") != stage["app_sha256"] or digest(root / "model/worker.json") != stage["model_manifest_sha256"]:
            raise ValueError("Promoted application/manifest mismatch")
        if digest(root / "db/index.sqlite") != stage["corpus_sha256"]:
            raise ValueError("Root canonical SQLite changed during promotion")
        record(journal, "promotion_complete", rollback_directory=str(backup))
    except BaseException as error:
        recovery_errors = []

        def recovery_record(event: str, **values) -> None:
            try:
                record(journal, event, **values)
            except BaseException as journal_error:
                recovery_errors.append({"operation": "journal", "event": event, "error": repr(journal_error)})

        recovery_record("promotion_failed", error=repr(error))
        # Never delete a failed replacement: move it back into the incoming area.
        for unit in reversed(moved_new):
            try:
                os.rename(native(root / unit), native(incoming / unit))
            except BaseException as restore_error:
                recovery_errors.append({"operation": "retain_replacement", "unit": unit, "error": repr(restore_error)})
                recovery_record("replacement_retention_failed", unit=unit, error=repr(restore_error))
            else:
                recovery_record("replacement_retained", unit=unit)
        for unit in reversed(moved_old):
            try:
                os.rename(native(backup / unit), native(root / unit))
            except BaseException as restore_error:
                recovery_errors.append({"operation": "restore_original", "unit": unit, "error": repr(restore_error)})
                recovery_record("original_restore_failed", unit=unit, error=repr(restore_error))
            else:
                recovery_record("original_restored", unit=unit)
        recovery_record("rollback_finished", recovery_errors=list(recovery_errors),
                        backup_directory=str(backup), incoming_directory=str(incoming))
        raise RuntimeError(
            f"Promotion failed: {error!r}. Best-effort rollback finished; recovery errors: "
            f"{json.dumps(recovery_errors, ensure_ascii=False)}. Close the application/worker before manual recovery. "
            f"Original units remain under {backup}; failed replacements remain under {incoming} or the root {root}. "
            "Preserve every existing unit: move a conflicting root unit into a new recovery directory, then move "
            "the corresponding saved original from backup to the root. Do not delete or overwrite files. "
            f"Journal: {journal}"
        ) from error
    print(json.dumps({**plan, "state": "promoted", "journal": str(journal)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
