"""Derive a body-policy sidecar without computing or changing embeddings.

The canonical SQLite database and the complete parent index are read-only.
The default historical ``nonblank`` policy removes only whitespace bodies.
``searchable`` requires a Unicode letter or number in the canonical body.
Retained vector bytes, JSONL lines and order are copied exactly. No title,
language, quality, score or length heuristic participates in this decision.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sqlite3
import unicodedata


DIMENSIONS = 384
ROW_BYTES = DIMENSIONS * 4
MIN_FREE_BYTES = 20 * 1024**3
RULE = "retain iff canonical SQLite chunks.text is a string and text.strip() != ''"
SEARCHABLE_RULE = "retain iff canonical SQLite chunks.text contains a Unicode letter or number (category L* or N*)"


def body_eligible(text: str, policy: str = "nonblank") -> bool:
    require(policy in ("nonblank", "searchable"), "Unknown body policy")
    if policy == "nonblank":
        return bool(text.strip())
    return any(unicodedata.category(character)[0] in "LN" for character in text)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024**2), b""):
            h.update(block)
    return h.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def checked_sha(value: object, label: str) -> str:
    require(isinstance(value, str) and len(value) == 64
            and all(c in "0123456789abcdef" for c in value),
            f"Invalid SHA-256: {label}")
    return value


def index_asset(directory: Path, declared: object) -> Path:
    """Accept both builder-local and packaged 'evidence/...' references."""
    require(isinstance(declared, str) and bool(declared), "Invalid asset path")
    relative = PurePosixPath(declared.replace("\\", "/"))
    require(not relative.is_absolute() and not any(p in ("..", "") for p in relative.parts)
            and ":" not in declared, "Unsafe asset path")
    candidates = [directory.joinpath(*relative.parts)]
    if relative.parts and relative.parts[0] == directory.name:
        candidates.append(directory.parent.joinpath(*relative.parts))
    found = set()
    for candidate in candidates:
        if candidate.is_file():
            resolved = candidate.resolve(strict=True)
            require(resolved.is_relative_to(directory), "Index asset escapes source directory")
            found.add(resolved)
    require(len(found) == 1, "Index asset missing or ambiguous: " + declared)
    return found.pop()


def file_record(path: Path) -> dict:
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": digest(path)}


def write_json(path: Path, value: dict) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def canonical_inventory(db: Path, body_policy: str = "nonblank") -> dict[str, tuple[str, bool, bool]]:
    # immutable=1 is safe only for an inert snapshot, not an uncheckpointed WAL.
    for suffix in ("-wal", "-journal"):
        sidecar = Path(str(db) + suffix)
        require(not sidecar.exists() or sidecar.stat().st_size == 0,
                "Canonical database has a live/nonempty SQLite sidecar")
    con = sqlite3.connect(db.as_uri() + "?mode=ro&immutable=1", uri=True)
    try:
        con.execute("PRAGMA query_only=ON")
        documents = {row[0] for row in con.execute("SELECT document_id FROM documents")}
        inventory = {}
        for fragment, document, text in con.execute(
                "SELECT fragment_id,document_id,text FROM chunks"):
            require(isinstance(fragment, str) and bool(fragment), "Invalid canonical fragment ID")
            require(fragment not in inventory, "Duplicate canonical fragment ID")
            require(isinstance(document, str) and document in documents,
                    "Invalid/orphan canonical document ID")
            require(isinstance(text, str), "Canonical body must be text, not NULL or a blob")
            inventory[fragment] = (document, body_eligible(text, body_policy), not text.strip())
        require(bool(inventory), "Empty canonical corpus")
        return inventory
    finally:
        con.close()


def derive(db: Path, source_index_dir: Path, out: Path,
           embedder: Path | None = None, *, min_free_bytes: int = MIN_FREE_BYTES,
           body_policy: str = "nonblank") -> dict:
    require(body_policy in ("nonblank", "searchable"), "Unknown body policy")
    db = db.resolve(strict=True)
    source_index_dir = source_index_dir.resolve(strict=True)
    out = out.resolve()
    require(not out.exists(), "Output already exists; choose a new directory")
    require(not out.is_relative_to(source_index_dir), "Output must not be inside source index")
    parent_file = source_index_dir / "index.json"
    parent_record = file_record(parent_file)
    parent = json.loads(parent_file.read_text(encoding="utf-8"))
    require(parent.get("schemaVersion") == 1, "Unsupported parent schema")
    require(type(parent.get("dimensions")) is int and parent["dimensions"] == DIMENSIONS,
            "Expected 384 dimensions")
    require(type(parent.get("rows")) is int and parent["rows"] > 0, "Invalid parent rows")
    require(parent.get("dtype") == "little-endian float32", "Expected little-endian FP32")
    require(parent.get("normalization") == "L2", "Expected L2-normalized parent")
    for name in ("corpusSha256", "embedderSha256", "vectorsSha256", "fragmentsSha256",
                 "selectionFreezeSha256"):
        checked_sha(parent.get(name), name)
    vectors = index_asset(source_index_dir, parent.get("vectors"))
    metadata = index_asset(source_index_dir, parent.get("fragments"))
    encoder = (embedder or source_index_dir / "encoder.onnx").resolve(strict=True)
    require(vectors != metadata and encoder not in (vectors, metadata, parent_file),
            "Index assets must be distinct")
    require(vectors.stat().st_size == parent["rows"] * ROW_BYTES, "Parent vector size mismatch")
    source_records = {"database": file_record(db), "index": parent_record,
                      "vectors": file_record(vectors), "fragments": file_record(metadata),
                      "embedder": file_record(encoder)}
    for label, key in (("database", "corpusSha256"), ("vectors", "vectorsSha256"),
                       ("fragments", "fragmentsSha256"), ("embedder", "embedderSha256")):
        require(source_records[label]["sha256"] == parent[key], f"Parent {label} SHA mismatch")
    inventory = canonical_inventory(db, body_policy)
    require(len(inventory) == parent["rows"], "Parent must cover the complete canonical corpus")
    seen = set()
    keep = []
    retained_ids = hashlib.sha256()
    with metadata.open("rb") as stream:
        for position, line in enumerate(stream):
            require(position < parent["rows"], "Parent metadata has too many rows")
            require(bool(line.strip()), "Blank parent metadata line")
            row = json.loads(line)
            require(isinstance(row, dict), "Parent metadata row must be an object")
            fragment, document = row.get("fragment_id"), row.get("document_id")
            require(isinstance(fragment, str) and fragment in inventory,
                    "Metadata fragment missing from canonical database")
            require(fragment not in seen, "Duplicate metadata fragment ID")
            require(document == inventory[fragment][0], "Metadata/canonical document mismatch")
            seen.add(fragment)
            retained = inventory[fragment][1]
            keep.append(retained)
            if retained:
                retained_ids.update(fragment.encode("utf-8") + b"\n")
    require(len(keep) == parent["rows"] and len(seen) == len(inventory),
            "Parent metadata row count/canonical inventory mismatch")
    retained_rows = sum(keep)
    require(retained_rows > 0, "No eligible canonical bodies remain")
    # Locate an existing ancestor without creating anything before validation.
    ancestor = out.parent
    while not ancestor.exists():
        ancestor = ancestor.parent
    estimated_output = retained_rows * ROW_BYTES + metadata.stat().st_size + 1024**2
    require(shutil.disk_usage(ancestor).free >= min_free_bytes + estimated_output,
            "Insufficient disk: output must leave at least 20 GiB free")
    out.mkdir(parents=True, exist_ok=False)
    vpartial, mpartial = out / "vectors.f32.partial", out / "fragments.jsonl.partial"
    parent_vector_sha, parent_metadata_sha = hashlib.sha256(), hashlib.sha256()
    output_vector_sha, output_metadata_sha = hashlib.sha256(), hashlib.sha256()
    with vectors.open("rb") as vin, metadata.open("rb") as minput, \
            vpartial.open("xb") as vout, mpartial.open("xb") as mout:
        for retained, line in zip(keep, minput, strict=True):
            vector = vin.read(ROW_BYTES)
            require(len(vector) == ROW_BYTES, "Parent vector truncated during derivation")
            parent_vector_sha.update(vector)
            parent_metadata_sha.update(line)
            if retained:
                vout.write(vector)
                mout.write(line)
                output_vector_sha.update(vector)
                output_metadata_sha.update(line)
        require(vin.read(1) == b"", "Parent vectors grew during derivation")
        for stream in (vout, mout):
            stream.flush()
            os.fsync(stream.fileno())
    require(parent_vector_sha.hexdigest() == parent["vectorsSha256"],
            "Parent vector bytes changed during derivation")
    require(parent_metadata_sha.hexdigest() == parent["fragmentsSha256"],
            "Parent metadata bytes changed during derivation")
    # Recheck all parent pins, including the database/model, before publication.
    for label, record in source_records.items():
        require(file_record(Path(record["path"])) == record, f"Parent {label} changed")
    require(vpartial.stat().st_size == retained_rows * ROW_BYTES, "Output vector size mismatch")
    require(digest(vpartial) == output_vector_sha.hexdigest()
            and digest(mpartial) == output_metadata_sha.hexdigest(), "Output write verification failed")
    os.rename(vpartial, out / "vectors.f32")
    os.rename(mpartial, out / "fragments.jsonl")
    rule = RULE if body_policy == "nonblank" else SEARCHABLE_RULE
    derivation = {
        "kind": "canonical-nonblank-body-subset-v1" if body_policy == "nonblank" else "canonical-searchable-body-subset-v2", "rule": rule,
        "parentIndexSha256": parent_record["sha256"],
        "parentVectorsSha256": parent["vectorsSha256"],
        "parentFragmentsSha256": parent["fragmentsSha256"],
        "parentSelectionFreezeSha256": parent["selectionFreezeSha256"],
        "parentRows": parent["rows"], "retainedRows": retained_rows,
        "removedBlankBodyRows": len(keep) - retained_rows,
        "rowBytes": ROW_BYTES, "retainedFragmentOrderSha256": retained_ids.hexdigest(),
        "canonicalDocumentMappingValidated": True,
        "completeParentCanonicalInventoryValidated": True,
        "retainedVectorBytesUnchanged": True, "retainedMetadataLinesUnchanged": True,
        "retainedRowOrderUnchanged": True, "reembeddingPerformed": False,
        "weightsChanged": False,
    }
    if body_policy == "searchable":
        blank_rows = sum(row[2] for row in inventory.values())
        derivation.update(bodyPolicy=body_policy,
                          removedUnsearchableBodyRows=len(keep) - retained_rows,
                          removedBlankBodyRows=blank_rows,
                          removedNonblankUnsearchableBodyRows=len(keep) - retained_rows - blank_rows,
                          canonicalPunctuationPreservedForClosure=True)
    result = dict(parent)
    result.update(rows=retained_rows, vectors="vectors.f32", fragments="fragments.jsonl",
                  vectorsSha256=output_vector_sha.hexdigest(),
                  fragmentsSha256=output_metadata_sha.hexdigest(), derivation=derivation)
    receipt = {"schema_version": 1, "state": "verified-derived-index",
               "rule": rule, "inputs": source_records, "derivation": derivation,
               "filter_code": file_record(Path(__file__).resolve()),
               "outputs": {name: file_record(out / name) for name in
                           ("vectors.f32", "fragments.jsonl")},
               "corpus_sha256_unchanged": result["corpusSha256"],
               "embedder_sha256_unchanged": result["embedderSha256"],
               "selection_freeze_sha256_unchanged": result["selectionFreezeSha256"],
               "no_corpus_or_parent_writes": True, "new_embeddings": False,
               "limitations": (["No retrieval/answer quality claim; blank bodies alone are excluded.",
                                "Unicode whitespace follows Python str.strip; zero-width non-whitespace is retained."]
                               if body_policy == "nonblank" else
                               ["Searchability only, not OCR quality or relevance: any letter or number suffices.",
                                "Canonical bodies remain unchanged and available by ID and for structural closure.",
                                "Letterless numeric bodies, including Roman and superscript numbers, remain indexed."])}
    write_json(out / "filter-receipt.json", receipt)
    # The loader-visible manifest is written last; partial failure never publishes it.
    write_json(out / "index.json", result)
    return {"out": str(out), "rows": retained_rows,
            **({"removed_blank_body_rows": len(keep) - retained_rows} if body_policy == "nonblank" else
               {"body_policy": body_policy,"removed_unsearchable_body_rows":len(keep) - retained_rows,
                "removed_blank_body_rows":blank_rows,
                "removed_nonblank_unsearchable_body_rows":len(keep) - retained_rows - blank_rows}),
            "corpus_sha256": result["corpusSha256"],
            "embedder_sha256": result["embedderSha256"],
            "files": {name: file_record(out / name) for name in
                      ("index.json", "vectors.f32", "fragments.jsonl", "filter-receipt.json")}}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--source-index-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--embedder", type=Path,
                        help="Verification-only encoder path; defaults to source-index-dir/encoder.onnx")
    parser.add_argument("--body-policy", choices=("nonblank", "searchable"), default="nonblank",
                        help="Historical nonblank default; searchable requires a Unicode letter or number")
    args = parser.parse_args()
    print(json.dumps(derive(args.db, args.source_index_dir, args.out, args.embedder, body_policy=args.body_policy),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
