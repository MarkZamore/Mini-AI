"""Build a pinned portable release from the *frozen* full application.

Planning and validation never publish or change the source runtime. Payload is
written directly as multipart ZIP64; no joined archive or staging copy is made.
Only the release maintainer needs Python. The recipient uses Windows PowerShell.
"""
from __future__ import annotations

import argparse
import bisect
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import zipfile

SCHEMA = 1
DEFAULT_PART_BYTES = 1792 * 1024 * 1024
GITHUB_ASSET_LIMIT = 2 * 1024**3
MIN_C_FREE = 20 * 1024**3
TEMPLATE_ROOT = Path(__file__).resolve().parent
REPOSITORY = "MarkZamore/Mini-AI"


def native(path: Path) -> Path:
    """Enable long paths per call without changing Windows machine settings."""
    value = str(path.absolute())
    if os.name == "nt" and not value.startswith("\\\\?\\"):
        return Path("\\\\?\\UNC\\" + value[2:] if value.startswith("\\\\") else "\\\\?\\" + value)
    return path


def sha(path: Path) -> str:
    with native(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def load(path: Path):
    return json.loads(native(path).read_text(encoding="utf-8-sig"))


def plain_absolute(path: Path) -> Path:
    value = str(path)
    if os.name == "nt" and value.startswith("\\\\?\\UNC\\"):
        value = "\\\\" + value[8:]
    elif os.name == "nt" and value.startswith("\\\\?\\"):
        value = value[4:]
    return Path(os.path.abspath(value))


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_relative(value: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError(f"Expected a slash-separated relative path: {value!r}")
    parts = value.split("/")
    if (any(not p or p in (".", "..") or ":" in p or p[-1] in " ."
            or re.search(r'[<>"|?*\x00-\x1f]', p)
            or re.fullmatch(r"(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?", p)
            for p in parts) or PurePosixPath(value).is_absolute()):
        raise ValueError(f"Unsafe Windows package path: {value!r}")
    return value


def ordinary_file(path: Path, anchor: Path) -> Path:
    """Reject links/reparse points, including ancestors inside the input root."""
    def unprefix(value):
        text = str(value)
        if os.name == "nt" and text.startswith("\\\\?\\UNC\\"):
            return Path("\\\\" + text[8:])
        if os.name == "nt" and text.startswith("\\\\?\\"):
            return Path(text[4:])
        return value
    anchor = unprefix(anchor).absolute()
    path = unprefix(path).absolute()
    path.relative_to(anchor)
    for current in (path, *path.parents):
        info = native(current).lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError(f"Linked input is unsupported: {current}")
        if current == anchor:
            break
    if not native(path).is_file():
        raise ValueError(f"Expected an ordinary file: {path}")
    return native(path)


def inventory_files(args):
    runtime = args.runtime_root.absolute()
    model = args.model_root.absolute()
    corpus = args.corpus_root.absolute()
    corpus_inventory = load(args.corpus_inventory)
    manifest_path = ordinary_file(model / "worker.json", model)
    model_manifest = load(manifest_path)
    if model_manifest.get("schemaVersion") != 2:
        raise ValueError("This release requires the accepted manifest v2")
    if not model_manifest.get("evidence"):
        raise ValueError("Full evidence-enabled model package is required")
    rows = {}
    folded = {}

    def add(source, relative, digest=None, size=None):
        relative = safe_relative(relative)
        if relative.casefold() in folded:
            raise ValueError(f"Duplicate Windows package path: {relative}")
        anchor = model if relative.startswith("model/") else corpus if relative.startswith(("docs/", "db/")) else runtime
        source = ordinary_file(Path(source), anchor)
        actual_size = source.stat().st_size
        actual_sha = sha(source)
        if digest is not None and digest != actual_sha:
            raise ValueError(f"SHA-256 mismatch: {relative}")
        if size is not None and int(size) != actual_size:
            raise ValueError(f"Size mismatch: {relative}")
        folded[relative.casefold()] = relative
        rows[relative] = dict(path=relative, bytes=actual_size, sha256=actual_sha, source=str(source))

    add(runtime / "Mini-AI.exe", "Mini-AI.exe")
    for name in ("vcruntime140.dll", "vcruntime140_1.dll"):
        if (runtime / name).exists():
            add(runtime / name, name)
    for folder in ("native", "licenses"):
        paths = sorted(p for p in (runtime / folder).rglob("*") if p.is_file())
        if folder == "licenses" and not paths:
            raise ValueError("Application licences are required")
        for path in paths:
            add(path, path.relative_to(runtime).as_posix())
    add(manifest_path, "model/worker.json")
    for entry in model_manifest["files"]:
        relative = safe_relative(entry["path"])
        add(model / relative, "model/" + relative, entry["sha256"])
    if not corpus_inventory.get("files"):
        raise ValueError("A verified nonempty corpus allowlist is required")
    for entry in corpus_inventory["files"]:
        relative = safe_relative(entry["path"])
        if not relative.startswith(("docs/", "db/")) or relative.startswith("db/_runtime/"):
            raise ValueError(f"Unexpected corpus allowlist entry: {relative}")
        add(corpus / relative, relative, entry["sha256"], entry["bytes"])
    if "db/index.sqlite" not in rows:
        raise ValueError("Canonical SQLite database is missing")
    if not any(p.startswith("db/artifacts/") for p in rows):
        raise ValueError("Source derivation artifacts are missing")
    for p in rows:
        parts = p.split("/")
        for i in range(1, len(parts)):
            if "/".join(parts[:i]).casefold() in folded:
                raise ValueError(f"A file is also used as a directory: {p}")
    return [rows[p] for p in sorted(rows)], model_manifest, sha(manifest_path)


def plan(args):
    if args.output.exists():
        raise ValueError("Use a new plan path; an existing plan is preserved")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,100}", args.tag):
        raise ValueError("Use a simple immutable release tag")
    rows, manifest, manifest_sha = inventory_files(args)
    result = {
        "schema_version": SCHEMA,
        "state": "planned-not-published",
        "configuration_id": args.configuration_id,
        "runtime_root": str(plain_absolute(args.runtime_root)),
        "corpus_inventory": str(plain_absolute(args.corpus_inventory)),
        "repository": REPOSITORY,
        "tag": args.tag,
        "app_sha256": next(r["sha256"] for r in rows if r["path"] == "Mini-AI.exe"),
        "model_manifest_sha256": manifest_sha,
        "corpus_sha256": next(r["sha256"] for r in rows if r["path"] == "db/index.sqlite"),
        "corpus_inventory_sha256": sha(args.corpus_inventory),
        "backend": manifest["backend"],
        "min_free_ram_bytes": manifest["minFreeMemoryBytes"],
        "full_composition": "All seven application modules; exact frozen runtime and model files inventory.",
        "file_count": len(rows),
        "unpacked_bytes": sum(r["bytes"] for r in rows),
        "files": rows,
    }
    write_json(args.output, result)
    print(json.dumps({k: v for k, v in result.items() if k != "files"}, ensure_ascii=False, indent=2))


class SplitZipWriter(io.RawIOBase):
    """Unseekable ZIP output with fixed-size, individually hashed part files."""
    def __init__(self, directory: Path, part_bytes: int):
        self.directory = directory
        self.part_bytes = part_bytes
        self.position = 0
        self.part = None
        self.part_size = 0
        self.part_hash = None
        self.parts = []

    def writable(self):
        return True

    def seekable(self):
        return False

    def tell(self):
        return self.position

    def _finish(self):
        if self.part is not None:
            self.part.flush()
            os.fsync(self.part.fileno())
            self.part.close()
            self.parts.append({"name": Path(self.part.name).name, "bytes": self.part_size, "sha256": self.part_hash.hexdigest()})
            self.part = None

    def write(self, data):
        original = len(data)
        view = memoryview(data)
        while view:
            if self.part is None:
                name = f"Mini-AI.payload.part{len(self.parts) + 1:03d}"
                self.part = (self.directory / name).open("xb")
                self.part_hash = hashlib.sha256()
                self.part_size = 0
            take = min(len(view), self.part_bytes - self.part_size)
            block = view[:take]
            if self.part.write(block) != take:
                raise OSError("Short archive write")
            self.part_hash.update(block)
            self.part_size += take
            self.position += take
            view = view[take:]
            if self.part_size == self.part_bytes:
                self._finish()
        return original

    def close(self):
        if not self.closed:
            self._finish()
        super().close()


class PartReader(io.RawIOBase):
    """Seekable archive view for a no-extraction, per-file integrity audit."""
    def __init__(self, paths):
        self.paths = paths
        self.starts = [0]
        for path in paths:
            self.starts.append(self.starts[-1] + path.stat().st_size)
        self.position = 0
        self.current = -1
        self.input = None

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        position = offset + (0 if whence == 0 else self.position if whence == 1 else self.starts[-1])
        if position < 0 or position > self.starts[-1]:
            raise ValueError("Seek outside multipart archive")
        self.position = position
        return position

    def readinto(self, buffer):
        if self.position == self.starts[-1] or not buffer:
            return 0
        index = bisect.bisect_right(self.starts, self.position) - 1
        if self.current != index:
            if self.input:
                self.input.close()
            self.input = self.paths[index].open("rb")
            self.current = index
        self.input.seek(self.position - self.starts[index])
        count = min(len(buffer), self.starts[index + 1] - self.position)
        got = self.input.readinto(memoryview(buffer)[:count])
        if not got:
            raise EOFError("Multipart asset changed during verification")
        self.position += got
        return got

    def close(self):
        if self.input:
            self.input.close()
        super().close()


def write_payload(rows, directory, part_bytes):
    """Verify each source while streaming to the ZIP. Never stage large files."""
    output = SplitZipWriter(directory, part_bytes)
    try:
        with zipfile.ZipFile(output, "w", allowZip64=True, compression=zipfile.ZIP_STORED) as archive:
            for i, row in enumerate(rows, 1):
                before = Path(row["source"]).stat()
                if before.st_size != row["bytes"]:
                    raise ValueError(f"Source size changed: {row['path']}")
                info = zipfile.ZipInfo(row["path"], date_time=(2026, 10, 24, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o100644 << 16
                digest = hashlib.sha256()
                count = 0
                with archive.open(info, "w", force_zip64=True) as target, Path(row["source"]).open("rb") as source:
                    while block := source.read(1024 * 1024):
                        target.write(block)
                        digest.update(block)
                        count += len(block)
                after = Path(row["source"]).stat()
                if (count != row["bytes"] or digest.hexdigest() != row["sha256"]
                        or before.st_size != after.st_size or before.st_mtime_ns != after.st_mtime_ns):
                    raise ValueError(f"Source changed during packaging: {row['path']}")
                if i % 100 == 0 or i == len(rows):
                    print(f"Packed {i}/{len(rows)} files", flush=True)
    finally:
        output.close()
    return output.parts


def validate_freeze(plan_value, freeze):
    if freeze.get("state") != "final-frozen":
        raise ValueError("Explicit final-frozen acceptance is required")
    for field in ("configuration_id", "app_sha256", "model_manifest_sha256", "corpus_sha256"):
        if freeze.get(field) != plan_value[field]:
            raise ValueError(f"Freeze and package plan differ: {field}")


def checked_record(record, expected_path=None):
    path = plain_absolute(Path(record["path"]))
    if expected_path is not None and path != plain_absolute(expected_path):
        raise ValueError("Pinned evidence path differs")
    ordinary_file(path, path.parent)
    if native(path).stat().st_size != record["bytes"] or sha(path) != record["sha256"]:
        raise ValueError(f"Pinned evidence bytes/SHA differ: {path}")
    return load(path)


def file_map(rows):
    result, folded = {}, set()
    for row in rows:
        name = safe_relative(row["path"])
        if name.casefold() in folded:
            raise ValueError(f"Duplicate Windows inventory path: {name}")
        folded.add(name.casefold())
        result[name] = (row["bytes"], row["sha256"])
    return result


def validate_decision(plan_value, frozen, freeze_path, decision_path):
    """Deploy an unchanged evaluated control after quality rejection, never a fake freeze."""
    decision = load(decision_path)
    if (decision.get("schema_version") != 2 or decision.get("state") != "operational-fallback"
            or decision.get("role") != "operational-fallback"
            or decision.get("evaluated_runtime_key") != "untrained"
            or decision.get("production_source_prompt_thresholds_changed") is not False
            or decision.get("new_weights_or_hyperparameters_selected") is not False
            or decision.get("independent_human_review") is not False
            or decision.get("normative_answer_quality_acceptance") != "not demonstrated for either configuration"):
        raise ValueError("Only an explicit unchanged operational-fallback decision is allowed")
    if frozen.get("state") != "final-frozen":
        raise ValueError("Original evaluation freeze is required")
    checked_record(decision["evaluation_freeze"], freeze_path)
    if not decision.get("authorized_by"):
        raise ValueError("Explicit deployment authorization is required")
    selected = frozen["configs"]["untrained"]
    runtime = plain_absolute(Path(decision["runtime_root"]))
    if (runtime != plain_absolute(Path(selected["runtime_root"]))
            or runtime != plain_absolute(Path(plan_value["runtime_root"]))):
        raise ValueError("Fallback runtime differs from the previously frozen untrained control")
    if decision["configuration_id"] != plan_value["configuration_id"]:
        raise ValueError("Fallback decision and package configuration differ")
    for key in ("app_sha256", "model_manifest_sha256", "corpus_sha256"):
        if decision[key] != plan_value[key]:
            raise ValueError(f"Fallback decision and plan differ: {key}")
    if (decision["app_sha256"] != frozen["app_sha256"]
            or decision["corpus_sha256"] != frozen["corpus_sha256"]
            or decision["model_manifest_sha256"] != selected["model_manifest_sha256"]
            or plan_value["backend"] != selected["backend"]):
        raise ValueError("Fallback fingerprints/backend differ from the evaluated control")

    stage = checked_record(decision["runtime_inventory"])
    if stage.get("state") != "full-runtime-staged" or plain_absolute(Path(stage["root"])) != runtime:
        raise ValueError("Pinned fallback stage belongs to another runtime")
    for key in ("app_sha256", "model_manifest_sha256", "corpus_sha256"):
        if stage[key] != decision[key]:
            raise ValueError(f"Fallback stage fingerprint differs: {key}")
    planned, staged = file_map(plan_value["files"]), file_map(stage["files"])
    if (planned != staged or len(planned) != stage["file_count"]
            or len(planned) != selected["expected_full_runtime_files"]
            or plan_value["file_count"] != len(planned)
            or sum(row[0] for row in planned.values()) != stage["unpacked_bytes"]
            or plan_value["unpacked_bytes"] != stage["unpacked_bytes"]):
        raise ValueError("Fallback plan must match every file in the exact full evaluated stage")
    for row in plan_value["files"]:
        if plain_absolute(Path(row["source"])) != runtime / row["path"]:
            raise ValueError("Fallback plan source is outside the exact evaluated runtime")
    frozen_rows = file_map([{**row, "path": plain_absolute(Path(row["path"])).relative_to(runtime).as_posix()}
                            for row in selected["inventory"]])
    if any(planned.get(name) != value for name, value in frozen_rows.items()):
        raise ValueError("Fallback stage changes a file from the original frozen inventory")
    extras = {name: value for name, value in planned.items() if name not in frozen_rows}
    audit = checked_record(decision["coverage_audit"])
    if (audit.get("freeze_modified") is not False or audit.get("post_run_companion") is not True
            or audit["freeze"] != decision["evaluation_freeze"]):
        raise ValueError("Long-path companion does not bind to the unchanged original freeze")
    coverage = audit["configs"]["untrained"]
    if (plain_absolute(Path(coverage["runtime_root"])) != runtime
            or coverage["frozen_inventory_count"] != len(frozen_rows)
            or coverage["native_enumerated_files"] != len(planned)
            or coverage["expected_stage_files"] != len(planned)
            or coverage["omitted_from_freeze_count"] != len(extras)
            or coverage["frozen_paths_absent_now"]
            or coverage.get("native_count_and_omitted_source_companion_pass") is not True):
        raise ValueError("Fallback coverage companion is incomplete")
    omitted = {}
    for proof in coverage["checked_omitted_originals"]:
        name = plain_absolute(Path(proof["path"])).relative_to(runtime).as_posix()
        key = name.casefold()
        if key in omitted:
            raise ValueError("Duplicate omitted-source proof")
        omitted[key] = proof
    if set(omitted) != {name.casefold() for name in extras}:
        raise ValueError("Only the separately anchored long original paths may supplement the freeze")
    frozen_folded = {name.casefold(): value for name, value in frozen_rows.items()}
    for name, (size, digest) in extras.items():
        proof = omitted[name.casefold()]
        if (not name.startswith("docs/") or proof["bytes"] != size
                or proof["actual_sha256"] != digest or proof["expected_source_sha256"] != digest
                or proof.get("source_sha_matches") is not True
                or proof.get("document_json_matches_frozen_sha") is not True):
            raise ValueError("Fallback extra file is not an unchanged original document")
        json_record = proof["frozen_document_json"]
        json_name = plain_absolute(Path(json_record["path"])).relative_to(runtime).as_posix()
        if (not json_name.startswith("db/documents/")
                or frozen_folded.get(json_name.casefold()) != (json_record["bytes"], json_record["sha256"])):
            raise ValueError("Original-source proof is not anchored to a frozen document JSON")
        document = checked_record(json_record)
        original = document["source"]
        if (document["document_id"] != proof["document_id"] or original["sha256"] != digest
                or original["size"] != size
                or plain_absolute(runtime / "db" / original["path"]) != runtime / name):
            raise ValueError("Frozen document JSON does not authenticate this long original")
    return {"state": "operational-fallback", "evaluated_runtime_key": "untrained",
            "decision_sha256": sha(decision_path), "quality_acceptance": "not-demonstrated",
            "notice": "Previously frozen foundation-E5 control. Fine-tuning did not pass acceptance; normative answers require source verification."}


def stage(args):
    """New full runtime through NTFS hardlinks; no copied weights/corpus."""
    output = args.output.absolute()
    if output.exists():
        raise ValueError("Use a new runtime stage; existing files are preserved")
    rows, _, model_sha = inventory_files(args)
    output.mkdir(parents=True)
    for row in rows:
        target = native(output / row["path"])
        target.parent.mkdir(parents=True, exist_ok=True)
        os.link(row["source"], target)
        source_info, target_info = Path(row["source"]).stat(), target.stat()
        if source_info.st_ino != target_info.st_ino or source_info.st_dev != target_info.st_dev:
            raise ValueError(f"Output is not the same verified file: {row['path']}")
    result = {"schema_version": SCHEMA, "state": "full-runtime-staged",
              "root": str(output), "file_count": len(rows), "hardlinked_payload_files": len(rows),
              "copied_payload_bytes": 0, "unpacked_bytes": sum(r["bytes"] for r in rows),
              "app_sha256": next(r["sha256"] for r in rows if r["path"] == "Mini-AI.exe"),
              "model_manifest_sha256": model_sha,
              "corpus_sha256": next(r["sha256"] for r in rows if r["path"] == "db/index.sqlite"),
              "files": [{k: r[k] for k in ("path", "bytes", "sha256")} for r in rows]}
    write_json(output.with_name(output.name + ".stage.json"), result)
    print(json.dumps({k: v for k, v in result.items() if k != "files"}, indent=2))


def build(args):
    value = load(args.plan)
    frozen = load(args.freeze)
    decision_path = getattr(args, "decision", None)
    if decision_path is not None:
        deployment = validate_decision(value, frozen, args.freeze, decision_path)
    else:
        validate_freeze(value, frozen)
        deployment = {"state": "final-frozen"}
    if value.get("schema_version") != SCHEMA:
        raise ValueError("Unknown plan schema")
    if not 4096 <= args.part_bytes < GITHUB_ASSET_LIMIT:
        raise ValueError("Each part must be at least 4 KiB and strictly below 2 GiB")
    output = args.output.absolute()
    if output.exists():
        raise ValueError("Use a new task output directory; existing output is preserved")
    if os.name == "nt":
        if output.drive.upper() != "D:":
            raise ValueError("Large release output belongs in a new directory on D:")
        if shutil.disk_usage("C:/").free < MIN_C_FREE:
            raise ValueError("Keep at least 20 GiB free on C:")
    ancestor = output.parent
    while not ancestor.exists():
        ancestor = ancestor.parent
    estimate = value["unpacked_bytes"] + len(value["files"]) * 512 + 16 * 1024**2
    if shutil.disk_usage(ancestor).free < estimate + 1024**3:
        raise ValueError("Not enough output space for payload and a 1 GiB reserve")
    output.mkdir(parents=True)
    bootstrap = output / "bootstrap" / "Mini-AI"
    bootstrap.mkdir(parents=True)
    state_path = output / "build-state.json"
    write_json(state_path, {"state": "building", "plan_sha256": sha(args.plan), "freeze_sha256": sha(args.freeze),
                            "deployment": deployment})
    parts = write_payload(value["files"], output, args.part_bytes)
    for part in parts:
        part["url"] = f"https://github.com/{REPOSITORY}/releases/download/{value['tag']}/{part['name']}"
    runtime_inventory = {
        "schema_version": SCHEMA, "configuration_id": value["configuration_id"],
        "files": [{k: row[k] for k in ("path", "bytes", "sha256")} for row in value["files"]],
    }
    inventory_path = bootstrap / "portable-files.json"
    write_json(inventory_path, runtime_inventory)
    release = {
        "schema_version": SCHEMA, "configuration_id": value["configuration_id"],
        "repository": REPOSITORY, "tag": value["tag"],
        "app_sha256": value["app_sha256"], "model_manifest_sha256": value["model_manifest_sha256"],
        "corpus_sha256": value["corpus_sha256"], "freeze_sha256": sha(args.freeze),
        "inventory_sha256": sha(inventory_path), "file_count": len(value["files"]),
        "unpacked_bytes": value["unpacked_bytes"], "archive_bytes": sum(r["bytes"] for r in parts),
        "parts": parts,
        "deployment": deployment,
    }
    profiles = bootstrap / "profiles"
    profiles.mkdir()
    worker = next(row for row in value["files"] if row["path"] == "model/worker.json")
    cuda = load(Path(worker["source"]))
    if cuda["backend"]["kind"] != "cuda":
        raise ValueError("Expected the frozen CUDA profile as the source of the explicit CPU alternative")
    (profiles / "worker.cuda.json").write_bytes(Path(worker["source"]).read_bytes())
    cpu = json.loads(json.dumps(cuda))
    cpu["backend"] = {"kind": "cpu", "device": 0, "gpuMemoryBudgetBytes": 0, "reserveFreeGpuMemoryBytes": 0}
    write_json(profiles / "worker.cpu.json", cpu)
    release["profiles"] = {
        "default": "cpu",
        "cpu": {"path": "profiles/worker.cpu.json", "bytes": (profiles / "worker.cpu.json").stat().st_size,
                "sha256": sha(profiles / "worker.cpu.json")},
        "cuda": {"path": "profiles/worker.cuda.json", "bytes": (profiles / "worker.cuda.json").stat().st_size,
                 "sha256": sha(profiles / "worker.cuda.json")},
        "notice": "Identical full application and model inventory; only the explicit generation backend changes.",
    }
    release_path = bootstrap / "portable-release.json"
    write_json(release_path, release)
    installer = (TEMPLATE_ROOT / "portable-setup.ps1").read_text(encoding="utf-8-sig")
    if installer.count("@@RELEASE_SHA256@@") != 1:
        raise ValueError("Installer hash placeholder missing or duplicated")
    installer = installer.replace("@@RELEASE_SHA256@@", sha(release_path))
    (bootstrap / "Portable-Setup.ps1").write_text(installer, encoding="utf-8-sig")
    launcher = (TEMPLATE_ROOT / "portable-launch.ps1").read_text(encoding="utf-8-sig")
    if launcher.count("@@RELEASE_SHA256@@") != 1:
        raise ValueError("Launcher hash placeholder missing or duplicated")
    (bootstrap / "Portable-Launch.ps1").write_text(
        launcher.replace("@@RELEASE_SHA256@@", sha(release_path)), encoding="utf-8-sig")
    shutil.copyfile(TEMPLATE_ROOT / "portable-setup.cmd", bootstrap / "Установить-и-запустить.cmd")
    for mode, name in (("cpu", "Запустить-на-процессоре.cmd"), ("cuda", "Запустить-на-NVIDIA.cmd")):
        command = ('@echo off\r\nsetlocal\r\n'
                   '"%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -NoProfile '
                   '-ExecutionPolicy Bypass -File "%~dp0Portable-Launch.ps1" -Mode ' + mode + ' %*\r\n'
                   'if errorlevel 1 pause\r\nendlocal\r\n')
        (bootstrap / name).write_text(command, encoding="ascii")
    shutil.copyfile(TEMPLATE_ROOT / "portable-README.md", bootstrap / "README.md")
    setup_path = output / "Mini-AI-setup.zip"
    with zipfile.ZipFile(setup_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(p for p in bootstrap.rglob("*") if p.is_file()):
            archive.write(path, "Mini-AI/" + path.relative_to(bootstrap).as_posix())
    published = [{"name": setup_path.name, "bytes": setup_path.stat().st_size, "sha256": sha(setup_path)}, *parts]
    (output / "SHA256SUMS.txt").write_text("".join(f"{r['sha256']}  {r['name']}\n" for r in published), encoding="ascii")
    write_json(output / "publication-assets.json", {
        "schema_version": SCHEMA, "state": "prepared-not-published",
        "tag": value["tag"], "configuration_id": value["configuration_id"],
        "plan_sha256": sha(args.plan), "freeze_sha256": sha(args.freeze),
        "deployment": deployment,
        "assets": [*published, {"name": "SHA256SUMS.txt", "bytes": (output / "SHA256SUMS.txt").stat().st_size,
                               "sha256": sha(output / "SHA256SUMS.txt")}],
    })
    write_json(state_path, {"state": "complete-not-published", "files": len(value["files"]),
                           "parts": len(parts), "plan_sha256": sha(args.plan), "freeze_sha256": sha(args.freeze),
                           "deployment": deployment})
    print(json.dumps({"output": str(output), "parts": len(parts), "archive_bytes": release["archive_bytes"],
                      "unpacked_bytes": release["unpacked_bytes"], "publication": "not performed"}, indent=2))


def verify(args):
    value = load(args.output / "publication-assets.json")
    for row in value["assets"]:
        path = args.output / safe_relative(row["name"])
        if "/" in row["name"] or path.stat().st_size != row["bytes"] or sha(path) != row["sha256"]:
            raise ValueError(f"Release asset differs: {row['name']}")
        if path.stat().st_size >= GITHUB_ASSET_LIMIT:
            raise ValueError(f"Asset violates GitHub size limit: {row['name']}")
    verified_files = None
    if args.deep:
        with zipfile.ZipFile(args.output / "Mini-AI-setup.zip") as setup:
            release_bytes = setup.read("Mini-AI/portable-release.json")
            release = json.loads(release_bytes)
            inventory_bytes = setup.read("Mini-AI/portable-files.json")
            if hashlib.sha256(inventory_bytes).hexdigest() != release["inventory_sha256"]:
                raise ValueError("Bootstrap inventory fingerprint differs")
            if hashlib.sha256(release_bytes).hexdigest().encode() not in setup.read("Mini-AI/Portable-Setup.ps1"):
                raise ValueError("Installer does not pin this exact release metadata")
            inventory = json.loads(inventory_bytes)
        rows = {safe_relative(r["path"]): r for r in inventory["files"]}
        if len(rows) != len(inventory["files"]):
            raise ValueError("Duplicate inventory paths")
        stream = PartReader([args.output / p["name"] for p in release["parts"]])
        with stream, zipfile.ZipFile(stream) as archive:
            entries = archive.infolist()
            if len(entries) != len(rows):
                raise ValueError("Payload entry count differs")
            seen = set()
            for entry in entries:
                name = safe_relative(entry.filename)
                if name in seen or name not in rows:
                    raise ValueError(f"Unexpected payload entry: {name}")
                seen.add(name)
                row = rows[name]
                with archive.open(entry) as source:
                    digest = hashlib.file_digest(source, "sha256").hexdigest()
                if entry.file_size != row["bytes"] or digest != row["sha256"]:
                    raise ValueError(f"Payload file differs: {name}")
            verified_files = len(seen)
    print(json.dumps({"assets_verified": len(value["assets"]), "payload_files_verified": verified_files,
                      "tag": value["tag"], "published": False}))


def main():
    parser = argparse.ArgumentParser(__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("plan")
    for name in ("runtime-root", "model-root", "corpus-root", "corpus-inventory", "output"):
        p.add_argument("--" + name, required=True, type=Path)
    p.add_argument("--tag", required=True)
    p.add_argument("--configuration-id", required=True)
    p.set_defaults(run=plan)
    p = commands.add_parser("stage")
    for name in ("runtime-root", "model-root", "corpus-root", "corpus-inventory", "output"):
        p.add_argument("--" + name, required=True, type=Path)
    p.set_defaults(run=stage)
    p = commands.add_parser("build")
    p.add_argument("--plan", required=True, type=Path)
    p.add_argument("--freeze", required=True, type=Path)
    p.add_argument("--decision", type=Path, help="Explicit operational-fallback decision linked to the original evaluation freeze")
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--part-bytes", type=int, default=DEFAULT_PART_BYTES)
    p.set_defaults(run=build)
    p = commands.add_parser("verify")
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--deep", action="store_true", help="Also stream every ZIP entry and verify file SHA, without extracting")
    p.set_defaults(run=verify)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
