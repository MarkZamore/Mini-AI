"""Assemble a SHA-verified, corpus-only runtime without changing canonical inputs.

Uses only allowlisted paths from the canonical SQLite and the pinned release
inventory. Existing matching files are hardlinked; absent originals are read
from the locally pinned data ZIP. No application, model, or corpus is published.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import sqlite3
import zipfile

PIN_INVENTORY = "6e45549454e6eac449d325f516addde87d36041fa22c6c6656f65170f370f06c"
PIN_ZIP = "8e2a0115d59ba208d7d14803e50f552f6df9f93f38c50c70f6ea1855d8ebe079"
PIN_SQLITE = "58d6d60adbe58e4e9fe17ba0e843d40bc56906bd1dde9a7b2d12276401d7399a"
MIN_FREE = 20 * 1024**3


def long_path(path: Path) -> Path:
    """Use the per-call Windows extended syntax; never alter system policy."""
    text = str(path.absolute())
    if os.name != "nt" or text.startswith("\\\\?\\"):
        return Path(text)
    if text.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + text[2:])
    return Path("\\\\?\\" + text)


def is_file(path: Path) -> bool:
    return long_path(path).is_file()


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with long_path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def relative_db_path(stored: str, kind: str) -> str:
    if not isinstance(stored, str) or "\\" in stored or "\0" in stored:
        raise ValueError(f"Invalid stored path: {stored!r}")
    value = posixpath.normpath("db/" + stored)
    prefix = "docs/" if kind == "original" else "db/"
    if not value.startswith(prefix) or ":" in value or PurePosixPath(value).is_absolute():
        raise ValueError(f"Path escaped corpus: {stored!r}")
    if any(p in ("", ".", "..") for p in PurePosixPath(value).parts):
        raise ValueError(f"Unsafe stored path: {stored!r}")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    project = args.project.resolve(strict=True)
    output = args.output.absolute()
    evidence = args.evidence.absolute()
    if output.exists() or evidence.exists():
        raise ValueError("Choose new output and evidence folders; previous results are preserved")
    if output == project or project in output.parents and "build" not in output.parts:
        raise ValueError("Output must be a separate research/build directory")
    if shutil.disk_usage(output.parent).free < MIN_FREE:
        raise ValueError("Less than 20 GiB available; refusing corpus preparation")

    inventory_path = project / "src/build/model-preparation/downloads/EnterpriseAI-files.json"
    zip_path = inventory_path.with_name("EnterpriseAI-data.zip")
    if sha(inventory_path) != PIN_INVENTORY or sha(zip_path) != PIN_ZIP:
        raise ValueError("Local release inventory/ZIP differs from the pinned release")
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    pinned = {}
    for row in inventory["files"]:
        if row["path"] in pinned:
            raise ValueError("Duplicate release inventory path")
        pinned[row["path"]] = row
    db_path = project / "db/index.sqlite"
    if sha(db_path) != PIN_SQLITE:
        raise ValueError("Canonical SQLite differs from the pinned corpus")
    db = sqlite3.connect(db_path.as_uri() + "?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    assert db.execute("pragma quick_check").fetchone()[0] == "ok"
    documents = [dict(x) for x in db.execute("select * from documents order by document_id")]
    artifacts = [dict(x) for x in db.execute("select * from artifacts order by document_id,artifact_id")]
    docs_by_id = {x["document_id"]: x for x in documents}
    if len(docs_by_id) != len(documents):
        raise ValueError("Duplicate document ID")
    by_artifact = {(x["document_id"], x["artifact_id"]): x for x in artifacts}
    if len(by_artifact) != len(artifacts):
        raise ValueError("Duplicate artifact ID")

    expected = {}
    def add(path: str, digest: str | None, kind: str) -> None:
        row = pinned.get(path)
        if not row or not re.fullmatch("[0-9a-f]{64}", row["sha256"]):
            raise ValueError(f"No pinned release inventory for {path}")
        if digest is not None and row["sha256"] != digest:
            raise ValueError(f"SQLite/release SHA disagreement for {path}")
        value = {"path": path, "bytes": row["size"], "sha256": row["sha256"], "kind": kind}
        if path in expected and expected[path] != value:
            raise ValueError(f"Conflicting required path: {path}")
        expected[path] = value

    add("db/index.sqlite", PIN_SQLITE, "sqlite")
    for document in documents:
        add(relative_db_path(document["source_path"], "original"), document["source_sha256"], "original")
        add(relative_db_path(document["json_path"], "canonical"), None, "canonical")
    for artifact in artifacts:
        if artifact["document_id"] not in docs_by_id:
            raise ValueError("Artifact references unknown document")
        add(relative_db_path(artifact["path"], "artifact"), artifact["sha256"], "artifact")
    for relative in ("db/catalog.json", "db/report.json", "db/schema/document.schema.json", "db/README.md"):
        add(relative, None, "metadata")

    # Prove every derivation terminates at the same document's original.
    chain_lengths = []
    for artifact in artifacts:
        current = artifact["artifact_id"]
        visited = set()
        while current != "original":
            key = (artifact["document_id"], current)
            if key not in by_artifact or current in visited or len(visited) >= 1024:
                raise ValueError(f"Broken/cyclic artifact chain: {key}")
            visited.add(current)
            current = by_artifact[key]["derived_from"]
        chain_lengths.append(len(visited))

    sources = [project, project / "src/build/laptop-release/clean-install-final/Mini-AI"]
    original_missing = {str(base): [] for base in sources}
    ordinary_missing_counts = {str(base): 0 for base in sources}
    for base in sources:
        for document in documents:
            relative = relative_db_path(document["source_path"], "original")
            path = base / relative
            if not path.is_file():
                ordinary_missing_counts[str(base)] += 1
            if not is_file(path):
                original_missing[str(base)].append({
                    "document_id": document["document_id"], "path": relative,
                    "absolute_characters": len(str(path)), "filename_characters": len(path.name),
                    "also_missing_with_windows_extended_prefix": not long_path(path).is_file(),
                })

    output.mkdir(parents=True)
    evidence.mkdir(parents=True)
    actions, rejections = [], []
    methods = collections.Counter()
    with zipfile.ZipFile(zip_path) as archive:
        entries = {info.filename: info for info in archive.infolist()}
        if len(entries) != len(archive.infolist()):
            raise ValueError("Duplicate ZIP entry names")
        for number, (relative, required) in enumerate(sorted(expected.items()), 1):
            target = output / relative
            long_path(target.parent).mkdir(parents=True, exist_ok=True)
            selected = None
            for base in sources:
                candidate = base / relative
                if not is_file(candidate):
                    continue
                if long_path(candidate).stat().st_size == required["bytes"] and sha(candidate) == required["sha256"]:
                    selected = candidate
                    break
                rejections.append({"path": str(candidate), "reason": "bytes_or_sha_mismatch"})
            if selected:
                # Same-volume links consume no second full copy. Never change
                # attributes or contents through a hardlink to canonical files.
                os.link(long_path(selected), long_path(target))
                action = {"path": relative, "method": "hardlink", "source": str(selected)}
            else:
                info = entries.get(relative)
                if info is None or info.file_size != required["bytes"] or info.is_dir():
                    raise ValueError(f"No exact pinned source for required file: {relative}")
                if shutil.disk_usage(output).free - info.file_size < MIN_FREE:
                    raise ValueError("Extraction would violate the 20 GiB reserve")
                h = hashlib.sha256()
                with archive.open(info, "r") as stream, long_path(target).open("xb") as destination:
                    for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
                        h.update(block)
                        destination.write(block)
                if h.hexdigest() != required["sha256"]:
                    raise ValueError(f"Extracted ZIP SHA mismatch: {relative}")
                action = {"path": relative, "method": "pinned_zip_extract", "source": str(zip_path),
                          "zip_entry": info.filename, "utf8_name_flag": bool(info.flag_bits & 2048)}
            if not is_file(target) or long_path(target).stat().st_size != required["bytes"] or sha(target) != required["sha256"]:
                raise ValueError(f"Assembled file failed SHA: {relative}")
            methods[action["method"]] += 1
            actions.append(action)
            if number % 500 == 0:
                print(json.dumps({"verified_files": number, "total": len(expected)}), flush=True)

    # Check canonical identity and every source_ref without altering the source.
    reference_counts = collections.Counter()
    canonical_refs = collections.Counter()
    def validate_ref(ref: dict, document: dict, counter) -> None:
        if ref.get("source_sha256") != document["source_sha256"]:
            raise ValueError(f"Wrong source version in locator: {document['document_id']}")
        aid = ref.get("artifact_id")
        if aid != "original" and (document["document_id"], aid) not in by_artifact:
            raise ValueError(f"Missing locator artifact: {document['document_id']} {aid}")
        counter[ref.get("kind", "unknown")] += 1
    def visit(value, document):
        if isinstance(value, dict):
            if "source_refs" in value:
                for ref in value["source_refs"]:
                    validate_ref(ref, document, canonical_refs)
            for key, child in value.items():
                if key != "source_refs":
                    visit(child, document)
        elif isinstance(value, list):
            for child in value:
                visit(child, document)
    for document in documents:
        value = json.loads(long_path(output / relative_db_path(document["json_path"], "canonical")).read_text(encoding="utf-8"))
        if value.get("schema_version") != "1.0" or value.get("document_id") != document["document_id"] or value.get("source_version_sha256") != document["source_sha256"]:
            raise ValueError("Canonical identity mismatch")
        if value["source"]["sha256"] != document["source_sha256"] or value["source"]["path"] != document["source_path"]:
            raise ValueError("Canonical source mismatch")
        for artifact in value["artifacts"]:
            row = by_artifact[(document["document_id"], artifact["id"])]
            if any(row[field] != artifact[field] for field in ("path", "sha256", "derived_from")):
                raise ValueError("Canonical artifact mismatch")
        visit(value, document)
    chunks = 0
    unique_locator_pairs = set()
    for row in db.execute("select document_id,source_refs from chunks order by rowid"):
        document = docs_by_id[row["document_id"]]
        for ref in json.loads(row["source_refs"]):
            validate_ref(ref, document, reference_counts)
            unique_locator_pairs.add((document["document_id"], ref["artifact_id"]))
        chunks += 1
    if sha(db_path) != PIN_SQLITE:
        raise ValueError("Canonical SQLite changed during the read-only assembly")

    files = [{key: value[key] for key in ("path", "bytes", "sha256")} for _, value in sorted(expected.items())]
    runtime_inventory = {"schema_version": 1, "root": str(output), "corpus_sha256": PIN_SQLITE, "files": files}
    write_json(evidence / "corpus-runtime-files.json", runtime_inventory)
    write_json(evidence / "assembly.json", {"sources": [str(x) for x in sources], "actions": actions, "rejected_files": rejections})
    write_json(evidence / "required-resolutions.json", {
        "schema_version": 1,
        "documents": [{"document_id": d["document_id"], "sha256": d["source_sha256"]} for d in documents],
        "artifacts": [{"document_id": a["document_id"], "artifact_id": a["artifact_id"], "source_sha256": docs_by_id[a["document_id"]]["source_sha256"], "sha256": a["sha256"]} for a in artifacts],
    })
    summary = {
        "schema_version": 1, "status": "sha_verified_python_pending_actual_java_resolver",
        "runtime_root": str(output), "corpus_sha256": PIN_SQLITE,
        "pinned_inventory_sha256": PIN_INVENTORY, "pinned_zip_sha256": PIN_ZIP,
        "documents": len(documents), "chunks": chunks, "artifact_rows": len(artifacts),
        "unique_artifact_files": sum(x["kind"] == "artifact" for x in expected.values()),
        "files": len(files), "logical_bytes": sum(x["bytes"] for x in files),
        "new_payload_bytes": sum(expected[x["path"]]["bytes"] for x in actions if x["method"] == "pinned_zip_extract"),
        "methods": dict(methods), "rejected_files": len(rejections),
        "initial_missing_originals": original_missing,
        "ordinary_python_missing_original_counts": ordinary_missing_counts,
        "artifact_chain_lengths": dict(collections.Counter(chain_lengths)),
        "chunk_locator_kinds": dict(reference_counts), "canonical_locator_kinds": dict(canonical_refs),
        "unique_chunk_document_artifact_pairs": len(unique_locator_pairs),
        "max_absolute_path_characters": max(len(str(output / f["path"])) for f in files),
        "long_absolute_paths_over_259": sum(len(str(output / f["path"])) > 259 for f in files),
        "free_disk_bytes": shutil.disk_usage(output).free,
        "limitations": ["Source existence and hashes do not prove OCR correctness or semantic support.",
                        "Actual Java DocumentsService resolution is recorded separately.",
                        "Hardlinked canonical inputs remain shared: never write through this research runtime."],
    }
    write_json(evidence / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
