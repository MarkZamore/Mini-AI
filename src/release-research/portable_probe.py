"""Small transport fixtures for the installer. Never an application acceptance.

No model inference, corpus evaluation, GUI screenshot or real application launch
is performed here. Disposable byte fixtures exercise Windows path and file safety.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("portable", HERE / "portable.py")
portable = importlib.util.module_from_spec(spec)
spec.loader.exec_module(portable)


def prepare(directory, case):
    directory.mkdir(parents=True)
    source = directory / "fixture-input"
    source.mkdir()
    data = {
        "Mini-AI.exe": b"TRANSPORT FIXTURE ONLY; NOT EXECUTABLE; NEVER LAUNCHED\n",
        "db/index.sqlite": b"TRANSPORT FIXTURE; NOT A DATABASE\n",
        "docs/" + "Длинный-проверочный-путь-" * 7 + "/" + "документ-" * 16 + ".txt": bytes(range(256)) * 1024,
        "db/artifacts/test.txt": b"evidence fixture\n",
        "model/worker.json": json.dumps({"backend": {"kind": "cuda", "device": 0, "gpuMemoryBudgetBytes": 8*1024**3,
                                                    "reserveFreeGpuMemoryBytes": 3*1024**3},
                                         "files": [], "evidence": "fixture"}, indent=2).encode(),
        "model/empty.bin": b"",
    }
    rows = []
    for index, (name, content) in enumerate(data.items()):
        path = source / str(index)
        path.write_bytes(content)
        rows.append({"path": name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(), "source": str(path)})
    downloads = directory / "downloads"
    downloads.mkdir()
    parts = portable.write_payload(rows, downloads, 65536)
    for part in parts:
        part["url"] = "https://github.com/MarkZamore/Mini-AI/releases/download/test-fixture/" + part["name"]
    profiles = directory / "profiles"
    profiles.mkdir()
    cuda_bytes = data["model/worker.json"]
    (profiles / "worker.cuda.json").write_bytes(cuda_bytes)
    cpu = json.loads(cuda_bytes)
    cpu["backend"] = {"kind": "cpu", "device": 0, "gpuMemoryBudgetBytes": 0, "reserveFreeGpuMemoryBytes": 0}
    portable.write_json(profiles / "worker.cpu.json", cpu)
    inventory = {"schema_version": 1, "configuration_id": "TRANSPORT-FIXTURE-NOT-APP",
                 "files": [{k: row[k] for k in ("path", "bytes", "sha256")} for row in rows]}
    if case == "traversal":
        inventory["files"][2]["path"] = "../escape-fixture.txt"
    portable.write_json(directory / "portable-files.json", inventory)
    release = {"schema_version": 1, "configuration_id": inventory["configuration_id"],
               "repository": "MarkZamore/Mini-AI", "tag": "test-fixture",
               "inventory_sha256": portable.sha(directory / "portable-files.json"),
               "file_count": len(rows), "parts": parts,
               "app_sha256": rows[0]["sha256"], "corpus_sha256": rows[1]["sha256"],
               "model_manifest_sha256": next(r["sha256"] for r in rows if r["path"] == "model/worker.json"),
               "profiles": {"default": "cpu"}}
    for mode in ("cpu", "cuda"):
        path = profiles / ("worker." + mode + ".json")
        release["profiles"][mode] = {"path": "profiles/" + path.name, "bytes": path.stat().st_size, "sha256": portable.sha(path)}
    portable.write_json(directory / "portable-release.json", release)
    pin = portable.sha(directory / "portable-release.json")
    for source_name, target_name in (("portable-setup.ps1", "Portable-Setup.ps1"), ("portable-launch.ps1", "Portable-Launch.ps1")):
        text = (HERE / source_name).read_text(encoding="utf-8-sig").replace("@@RELEASE_SHA256@@", pin)
        (directory / target_name).write_text(text, encoding="utf-8-sig")
    if case == "resume-extract":
        name = next(p for p in data if p.startswith("docs/"))
        target = portable.native(directory / (name + ".mini-ai.partial"))
        target.parent.mkdir(parents=True)
        target.write_bytes(data[name][:17003])
        portable.write_json(directory / "portable-install-state.json",
                            {"inventory_sha256": release["inventory_sha256"], "state": "preparing"})
    if case == "different-existing":
        (directory / "Mini-AI.exe").write_bytes(b"USER CONTENT MUST BE PRESERVED")
    if case == "bad-part":
        part_path = downloads / parts[0]["name"]
        content = bytearray(part_path.read_bytes())
        content[100] ^= 1
        part_path.write_bytes(content)
    return data


def invoke(directory, *args):
    command = [str(Path("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe")),
               "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(directory / "Portable-Setup.ps1"),
               "-Offline", "-NoLaunch", *args]
    return subprocess.run(command, capture_output=True, timeout=90, encoding="utf-8", errors="replace")


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.absolute()
    if output.exists():
        raise ValueError("Use a new transport-probe output; all earlier fixtures are preserved")
    results = []
    for case in ("complete", "resume-extract", "long-base", "different-existing", "bad-part", "traversal"):
        directory = output / case
        if case == "long-base":
            directory = directory / ("длинная-папка-" * 8) / ("проверка-пути-" * 8)
            if len(str(directory.absolute())) <= 300:
                raise AssertionError("Long-base regression must really exceed 300 characters")
        directory = portable.native(directory)
        data = prepare(directory, case)
        run = invoke(directory)
        (directory / "setup-output.txt").write_text(run.stdout + run.stderr, encoding="utf-8")
        expected = case in ("complete", "resume-extract", "long-base")
        passed = (run.returncode == 0) == expected
        detail = {"case": case, "exit_code": run.returncode, "expected_success": expected}
        if case == "long-base":
            detail["base_path_characters"] = len(str(directory))
            detail["extended_file_argument"] = str(directory).startswith("\\\\?\\")
        if expected and run.returncode == 0:
            # The explicit CPU switch changes only worker.json. Every transported
            # fixture byte and the immutable CUDA profile must otherwise match.
            for name, content in data.items():
                actual = portable.native(directory / name).read_bytes()
                if name == "model/worker.json":
                    passed &= actual == (directory / "profiles/worker.cpu.json").read_bytes()
                else:
                    passed &= actual == content
            verify = invoke(directory, "-VerifyOnly")
            detail["verify_exit_code"] = verify.returncode
            passed &= verify.returncode == 0
            repeated = invoke(directory)
            detail["repeat_exit_code"] = repeated.returncode
            passed &= repeated.returncode == 0
        if case == "different-existing":
            passed &= (directory / "Mini-AI.exe").read_bytes() == b"USER CONTENT MUST BE PRESERVED"
        if case == "traversal":
            passed &= not (output / "escape-fixture.txt").exists()
        detail["passed"] = bool(passed)
        results.append(detail)
    result = {"scope": "Transport fixtures only; no actual application or model acceptance.",
              "cases": results, "passed": all(r["passed"] for r in results)}
    portable.write_json(output / "result.json", result)
    print(json.dumps(result, indent=2))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
