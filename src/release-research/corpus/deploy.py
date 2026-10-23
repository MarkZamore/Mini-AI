"""Plan/apply missing corpus files; explicit, identity-checked rollback only.

Default operation is a read-only plan. Corpus content is never overwritten.
Only --apply creates missing hardlinks. Rollback additionally requires the
original successful addition identity, expected content, and an explicit apply.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import uuid

_spec = importlib.util.spec_from_file_location("corpus_portable", Path(__file__).resolve().parents[1] / "corpus_portable.py")
_corpus = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_corpus)
long_path, sha = _corpus.long_path, _corpus.sha
WORKSPACE = Path(__file__).resolve().parents[3]
CORPUS_SHA = "58d6d60adbe58e4e9fe17ba0e843d40bc56906bd1dde9a7b2d12276401d7399a"
INVENTORY_SHA = "be47e3cb1b329e801984de2299c3d2e704ff7b6ad7295c2eb09eebebae0737ec"
MIN_FREE = 20 * 1024**3


def plain_regular(path: Path) -> bool:
    value = long_path(path).lstat()
    return stat.S_ISREG(value.st_mode) and not getattr(value, "st_file_attributes", 0) & 0x400


def identity(path: Path) -> dict:
    value = long_path(path).stat()
    return {"device": value.st_dev, "inode": value.st_ino, "bytes": value.st_size, "mtime_ns": value.st_mtime_ns}


def child(root: Path, relative: str) -> Path:
    if not isinstance(relative, str) or "\\" in relative or ":" in relative or "\0" in relative:
        raise ValueError(f"Unsafe corpus path: {relative!r}")
    pieces = PurePosixPath(relative).parts
    if not pieces or pieces[0] not in ("docs", "db") or any(x in ("", ".", "..") for x in pieces):
        raise ValueError(f"Path outside corpus allowlist: {relative!r}")
    target = root.joinpath(*pieces)
    target.relative_to(root)
    # Refuse every junction/symlink in existing ancestors, even those that happen
    # to resolve within the same workspace today.
    current = root
    for piece in pieces:
        current = current / piece
        if long_path(current).exists() or long_path(current).is_symlink():
            value = long_path(current).lstat()
            if stat.S_ISLNK(value.st_mode) or getattr(value, "st_file_attributes", 0) & 0x400:
                raise ValueError(f"Reparse path is not accepted: {current}")
    resolved = long_path(target.parent).resolve(strict=False)
    root_resolved = long_path(root).resolve(strict=True)
    resolved.relative_to(root_resolved)
    return target


def verify_file(path: Path, row: dict) -> None:
    if not plain_regular(path) or identity(path)["bytes"] != row["bytes"] or sha(path) != row["sha256"]:
        raise ValueError(f"Corpus content differs from approved inventory: {path}")


def index_sha(root: Path) -> str:
    path = child(root, "db/index.sqlite")
    if not long_path(path).is_file() or sha(path) != CORPUS_SHA:
        raise ValueError("Target/source canonical SQLite SHA differs from the approved corpus")
    return CORPUS_SHA


def target_root(value: Path) -> Path:
    root = value.resolve(strict=True)
    workspace = WORKSPACE.resolve(strict=True)
    root.relative_to(workspace)
    if root != workspace:
        raise ValueError("This deployment is pinned to the project root, not another output directory")
    if root.is_symlink() or getattr(root.lstat(), "st_file_attributes", 0) & 0x400:
        raise ValueError("Workspace root must not be a reparse point")
    return root


class Journal:
    def __init__(self, path: Path):
        self.path = path.resolve(strict=False)
        self.path.relative_to((WORKSPACE / "src/build/research-20261024").resolve(strict=True))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.stream = self.path.open("x", encoding="utf-8", newline="\n")

    def write(self, event: dict) -> None:
        self.stream.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
        self.stream.flush()
        os.fsync(self.stream.fileno())

    def close(self) -> None:
        self.stream.close()


def deploy(args) -> None:
    target = target_root(args.target)
    source = args.source.resolve(strict=True)
    source.relative_to(WORKSPACE / "src/build/research-20261024")
    if source == target:
        raise ValueError("Source and target must differ")
    if sha(args.inventory) != INVENTORY_SHA:
        raise ValueError("Inventory differs from the approved corpus runtime manifest")
    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    if inventory.get("schema_version") != 1 or inventory.get("corpus_sha256") != CORPUS_SHA or Path(inventory["root"]).resolve(strict=True) != source:
        raise ValueError("Inventory corpus identity/root mismatch")
    rows = inventory["files"]
    if len({row["path"] for row in rows}) != len(rows):
        raise ValueError("Duplicate inventory path")
    index_sha(target); index_sha(source)
    if shutil.disk_usage(target).free < MIN_FREE:
        raise ValueError("C: reserve below 20 GiB; deployment refused")
    journal = Journal(args.journal)
    additions, conflicts, existing = [], [], 0
    run_id = str(uuid.uuid4())
    journal.write({"event": "header", "schema_version": 1, "run_id": run_id,
                   "mode": "apply" if args.apply else "plan", "target": str(target), "source": str(source),
                   "inventory_sha256": INVENTORY_SHA, "corpus_sha256": CORPUS_SHA})
    try:
        # Finish the full preflight before creating any corpus directory/file.
        for number, row in enumerate(rows, 1):
            original = child(source, row["path"])
            destination = child(target, row["path"])
            verify_file(original, row)
            if long_path(destination).exists():
                try:
                    verify_file(destination, row)
                    existing += 1
                except Exception as error:
                    conflicts.append({"path": row["path"], "reason": str(error)})
            else:
                if row["path"] == "db/index.sqlite":
                    raise ValueError("The canonical target SQLite must already exist and is never added")
                additions.append({"path": row["path"], "bytes": row["bytes"], "sha256": row["sha256"], "source_identity": identity(original)})
            if number % 1000 == 0:
                print(json.dumps({"preflight_files": number, "total": len(rows)}), flush=True)
        for conflict in conflicts:
            journal.write({"event": "conflict", **conflict})
        journal.write({"event": "preflight", "existing_matching": existing, "missing": len(additions), "conflicts": len(conflicts),
                       "missing_logical_bytes": sum(row["bytes"] for row in additions), "free_disk_bytes": shutil.disk_usage(target).free})
        if conflicts:
            raise ValueError(f"{len(conflicts)} existing files differ; nothing added or overwritten")
        index_sha(target)
        added = 0
        for row in additions:
            journal.write({"event": "planned_add" if args.apply else "would_add", **row})
            if not args.apply:
                continue
            if shutil.disk_usage(target).free < MIN_FREE:
                raise ValueError("Free disk reserve fell below 20 GiB; stop without automatic rollback")
            original = child(source, row["path"])
            destination = child(target, row["path"])
            if identity(original) != row["source_identity"]:
                raise ValueError("Source changed after preflight")
            long_path(destination.parent).mkdir(parents=True, exist_ok=True)
            child(target, row["path"])  # Recheck the created parent before link.
            try:
                os.link(long_path(original), long_path(destination))
            except FileExistsError:
                verify_file(destination, row)
                journal.write({"event": "skipped_existing_after_plan", "path": row["path"]})
                continue
            verify_file(destination, row)
            journal.write({"event": "added", "path": row["path"], "bytes": row["bytes"], "sha256": row["sha256"], "identity": identity(destination)})
            added += 1
        after = index_sha(target)
        journal.write({"event": "completed", "status": "PASS", "mode": "apply" if args.apply else "plan",
                       "added": added, "missing": len(additions), "existing_matching": existing, "sqlite_sha256_after": after})
        print(json.dumps({"status": "PASS", "mode": "apply" if args.apply else "plan", "added": added,
                          "missing": len(additions), "existing_matching": existing, "journal": str(journal.path)}, ensure_ascii=False), flush=True)
    except BaseException as error:
        journal.write({"event": "stopped", "error": str(error), "automatic_rollback": False})
        raise
    finally:
        journal.close()


def rollback(args) -> None:
    target = target_root(args.target)
    events = [json.loads(line) for line in args.rollback_journal.read_text(encoding="utf-8").splitlines() if line]
    header = events[0]
    if header.get("event") != "header" or header.get("mode") != "apply" or header.get("target") != str(target) or header.get("inventory_sha256") != INVENTORY_SHA or header.get("corpus_sha256") != CORPUS_SHA:
        raise ValueError("Rollback requires the original apply journal for this exact target/inventory")
    source = Path(header["source"]).resolve(strict=True)
    source.relative_to(WORKSPACE / "src/build/research-20261024")
    planned, added, skipped = {}, {}, set()
    for event in events[1:]:
        if event["event"] == "planned_add": planned[event["path"]] = event
        elif event["event"] == "added": added[event["path"]] = event
        elif event["event"] == "skipped_existing_after_plan": skipped.add(event["path"])
    index_sha(target)
    removals = []
    for relative, event in added.items():
        if relative in skipped: continue
        if relative == "db/index.sqlite": raise ValueError("Rollback must never remove canonical SQLite")
        path = child(target, relative)
        if not long_path(path).exists(): continue
        verify_file(path, event)
        # Only a completed 'added' record proves ownership. A crash between the
        # link and that record leaves an unowned pending path for manual audit;
        # rollback must not guess that such a file was created by this script.
        expected = event["identity"]
        if identity(path) != expected:
            raise ValueError(f"File identity changed; refuse to remove {relative}")
        removals.append((relative, path, expected))
    journal = Journal(args.journal)
    journal.write({"event": "rollback_header", "source_journal": str(args.rollback_journal.absolute()), "target": str(target),
                   "mode": "apply" if args.apply else "plan", "candidate_files": len(removals),
                   "unowned_pending_paths_not_removed": [relative for relative in planned if relative not in added and relative not in skipped]})
    try:
        for relative, path, expected in reversed(removals):
            if identity(path) != expected: raise ValueError("File identity changed after rollback preflight")
            journal.write({"event": "will_remove" if args.apply else "would_remove", "path": relative, "identity": expected})
            if args.apply:
                long_path(path).unlink()
                journal.write({"event": "removed", "path": relative})
        index_sha(target)
        journal.write({"event": "completed", "status": "PASS", "mode": "rollback_apply" if args.apply else "rollback_plan",
                       "files": len(removals), "sqlite_sha256_after": CORPUS_SHA})
        print(json.dumps({"status": "PASS", "mode": "rollback_apply" if args.apply else "rollback_plan", "files": len(removals), "journal": str(journal.path)}))
    finally:
        journal.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--inventory", type=Path)
    parser.add_argument("--journal", type=Path, required=True, help="New journal inside this project's research-20261024 folder")
    parser.add_argument("--apply", action="store_true", help="Explicitly add missing files, or explicitly apply requested rollback")
    parser.add_argument("--rollback-journal", type=Path)
    args = parser.parse_args()
    if args.rollback_journal:
        rollback(args)
    else:
        if not args.source or not args.inventory: parser.error("--source and --inventory are required for deployment")
        deploy(args)


if __name__ == "__main__":
    main()
