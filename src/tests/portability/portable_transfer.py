"""Developer-only relocation/failure probes. Writes fixtures/reports under src/build only."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import ctypes
from ctypes import wintypes

ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / "src" / "build" / "test-results"

def extended(path: Path) -> str:
    value = str(path.absolute())
    if len(value) < 248 or value.startswith("\\\\?\\"):
        return value
    return "\\\\?\\UNC\\" + value[2:] if value.startswith("\\\\") else "\\\\?\\" + value

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(extended(path), "rb") as stream:
        while data := stream.read(1024 * 1024):
            digest.update(data)
    return digest.hexdigest()

def canonical(value: str | Path) -> str:
    value = str(value)
    if value.startswith("\\\\?\\UNC\\"):
        value = "\\\\" + value[8:]
    elif value.startswith("\\\\?\\"):
        value = value[4:]
    return os.path.normcase(os.path.normpath(value))

def files(directory: Path):
    walk_root = str(directory.absolute())
    if not walk_root.startswith("\\\\?\\"):
        walk_root = "\\\\?\\UNC\\" + walk_root[2:] if walk_root.startswith("\\\\") else "\\\\?\\" + walk_root
    for folder, _, names in os.walk(walk_root):
        for name in names:
            path = Path(folder) / name
            yield os.path.relpath(str(path), walk_root), path

def inventory(directory: Path) -> dict:
    return {relative: {"bytes": path.stat().st_size, "sha256": sha256(path)} for relative, path in files(directory)}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--exe", default=str(ROOT / "EnterpriseAI.exe"))
    args = parser.parse_args()
    original_exe = Path(args.exe).absolute()
    original_db = ROOT / "db" / "index.sqlite"
    base = BUILD / "portable-transfer" / ("session-" + uuid.uuid4().hex)
    base.mkdir(parents=True, exist_ok=False)
    if not base.is_relative_to(BUILD):
        raise RuntimeError("Fixture path escaped the allowed build directory")
    report_file = BUILD / "portable-transfer.json"
    results = []
    report = {"completed": False, "cases": results, "fixtureRoot": str(base)}
    original_hashes = {"exe": sha256(original_exe), "index.sqlite": sha256(original_db)}
    protected_dir = base / "observer-temporary"
    protected_dir.mkdir()
    env = dict(os.environ)
    for name in ("JAVA_HOME", "PYTHONHOME", "PYTHONPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(name, None)
    windows = Path(env["SYSTEMROOT"])
    env["PATH"] = str(windows / "System32") + os.pathsep + str(windows)
    env["TEMP"] = env["TMP"] = str(protected_dir)

    def run(exe: Path, arguments: list[str], expected_code=0, timeout=25):
        started = time.perf_counter()
        child = subprocess.Popen([extended(exe), *arguments], executable=extended(exe), cwd=windows, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="strict")
        loaded_runtime = {}
        if arguments == ["--ui-smoke"]:
            # Read the owned process's loaded modules to prove use of app-local CRT,
            # rather than a same-named installed System32 copy hidden by PATH isolation.
            psapi = ctypes.WinDLL("psapi", use_last_error=True)
            psapi.EnumProcessModulesEx.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE), wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), wintypes.DWORD]
            psapi.EnumProcessModulesEx.restype = wintypes.BOOL
            psapi.GetModuleFileNameExW.argtypes = [wintypes.HANDLE, wintypes.HMODULE, wintypes.LPWSTR, wintypes.DWORD]
            psapi.GetModuleFileNameExW.restype = wintypes.DWORD
            for attempt in range(20):
                handles = (wintypes.HMODULE * 1024)(); size = wintypes.DWORD()
                if psapi.EnumProcessModulesEx(wintypes.HANDLE(int(child._handle)), handles, ctypes.sizeof(handles), ctypes.byref(size), 3):
                    for module in handles[:size.value // ctypes.sizeof(wintypes.HMODULE)]:
                        path = ctypes.create_unicode_buffer(32768)
                        if psapi.GetModuleFileNameExW(wintypes.HANDLE(int(child._handle)), module, path, len(path)):
                            name = os.path.basename(path.value).lower()
                            if name in ("vcruntime140.dll", "vcruntime140_1.dll"):
                                loaded_runtime[name] = path.value
                if len(loaded_runtime) == 2: break
                time.sleep(0.05)
        stdout, stderr = child.communicate(timeout=timeout)
        process = subprocess.CompletedProcess(child.args, child.returncode, stdout, stderr)
        if process.returncode != expected_code:
            raise AssertionError(f"Unexpected exit {process.returncode}: {arguments}: {process.stdout} {process.stderr}")
        if process.stderr:
            raise AssertionError(f"Unexpected stderr: {arguments}: {process.stderr}")
        payload = json.loads(process.stdout)
        if arguments == ["--ui-smoke"]:
            for name in ("vcruntime140.dll", "vcruntime140_1.dll"):
                if canonical(loaded_runtime.get(name, "")) != canonical(exe.parent / name):
                    raise AssertionError("MSVC runtime was not loaded from the portable EXE directory: " + name)
            payload["observerLoadedRuntime"] = loaded_runtime
        return payload, round((time.perf_counter()-started)*1000, 3)

    def fixture(name: str | Path, database: str):
        folder = base / name
        os.makedirs(extended(folder), exist_ok=False)
        shutil.copyfile(extended(original_exe), extended(folder / "EnterpriseAI.exe"))
        for library in original_exe.parent.glob("vcruntime*.dll"):
            shutil.copyfile(extended(library), extended(folder / library.name))
        native = folder / "native"
        os.mkdir(extended(native))
        for library in (original_exe.parent / "native").glob("*.dll"):
            shutil.copyfile(extended(library), extended(native / library.name))
        if database != "absent":
            os.mkdir(extended(folder / "db"))
            if database == "normal":
                shutil.copyfile(extended(original_db), extended(folder / "db" / "index.sqlite"))
            elif database == "corrupt":
                (folder / "db" / "index.sqlite").write_bytes(b"This is intentionally not SQLite")
        return folder

    try:
        reference = {}
        for query in ("академическая политика", "Қазақстан", "құжат"):
            hits, _ = run(original_exe, ["--search", query])
            if not hits:
                raise AssertionError("Reference query has no results: " + query)
            reference[query] = hits
        normal = fixture("Перенос с пробелами Қазақ ә ғ ң 😀", "normal")
        tail = Path("Длинный путь перенос Қазақ с пробелами")
        while len(str(base / tail / "EnterpriseAI.exe")) <= 340:
            tail /= "Следующая папка длинного пути 0123456789"
        long = fixture(tail, "normal")
        missing = fixture("отсутствуют docs db model", "absent")
        corrupt = fixture("повреждён индекс без docs model", "corrupt")
        native_missing = base / "нет native DLL"
        native_missing.mkdir()
        shutil.copyfile(extended(original_exe), extended(native_missing / "EnterpriseAI.exe"))
        for library in original_exe.parent.glob("vcruntime*.dll"):
            shutil.copyfile(extended(library), extended(native_missing / library.name))
        before = inventory(base)
        for label, folder in (("unicode-space", normal), ("long-path", long)):
            exe = folder / "EnterpriseAI.exe"
            status, ms = run(exe, ["--status"])
            if canonical(status["root"]) != canonical(folder):
                raise AssertionError("Application did not resolve its actual copied EXE directory")
            for query, expected in reference.items():
                actual, _ = run(exe, ["--search", query])
                if actual != expected:
                    raise AssertionError(f"Relocated search changed results/provenance: {label}: {query}")
            graph, graph_ms = run(exe, ["--graph"])
            if len(graph["nodes"]) != 599 or len(graph["edges"]) != 647:
                raise AssertionError("Relocated graph omitted documents or confirmed relations")
            model, _ = run(exe, ["--model-status"])
            if model["success"] or model["error"]["code"] != "MODEL_FOLDER_MISSING":
                raise AssertionError("Missing model did not produce its explicit independent error")
            document_id = graph["nodes"][0]["document"]["document_id"]
            opened, _ = run(exe, ["--open", document_id], expected_code=1)
            if opened["success"]:
                raise AssertionError("Missing original unexpectedly opened")
            smoke, smoke_ms = run(exe, ["--ui-smoke"], timeout=20)
            if smoke.get("uiSmoke") is not True:
                raise AssertionError("Relocated UI smoke did not complete")
            expected_notice = len(str(exe).encode("utf-16-le")) // 2 >= 256
            if bool(smoke.get("inputNotice")) != expected_notice:
                raise AssertionError("Long-path input compatibility notice was missing or unexpected")
            results.append({"case": label, "passed": True, "root": status["root"], "exePathLength": len(str(exe)),
                "statusMs": ms, "graphMs": graph_ms, "uiSmokeMs": smoke_ms, "documents": 599, "confirmedEdges": 647,
                "missingDocsError": opened["error"], "missingModelError": model["error"], "inputNotice":smoke.get("inputNotice", ""), "observerLoadedRuntime":smoke.get("observerLoadedRuntime")})
        for label, folder in (("missing-data", missing), ("corrupt-db", corrupt)):
            exe = folder / "EnterpriseAI.exe"
            status, _ = run(exe, ["--status"])
            if not status["diagnostics"] or status["panels"] != ["assistant", "graph"]:
                raise AssertionError("Failure state lost the independent shell/panels")
            search, _ = run(exe, ["--search", "академическая политика"], expected_code=1)
            expected_error = "DB_MISSING" if label == "missing-data" else "DB_QUERY"
            if search["success"] or search["error"]["code"] != expected_error:
                raise AssertionError("Missing/failed database did not return an explicit error")
            smoke, _ = run(exe, ["--ui-smoke"], timeout=20)
            if smoke.get("uiSmoke") is not True:
                raise AssertionError("Failure state UI was not live")
            results.append({"case": label, "passed": True, "diagnostics": status["diagnostics"], "searchError": search["error"]})
        failure, _ = run(native_missing / "EnterpriseAI.exe", ["--status"], expected_code=1)
        if failure["success"] or failure["error"]["code"] != "NATIVE_MISSING":
            raise AssertionError("Missing native DLL did not fail directly")
        results.append({"case": "missing-native-no-extraction", "passed": True, "error": failure["error"]})
        after = inventory(base)
        sidecars = [relative for relative, path in files(base) if any(
            path.name.endswith(suffix) for suffix in ("-journal", "-wal", "-shm", ".hprof", ".dmp"))]
        if before != after or sidecars:
            raise AssertionError("Runtime changed copied inputs, created files or sidecars")
        final_hashes = {"exe": sha256(original_exe), "index.sqlite": sha256(original_db)}
        if original_hashes != final_hashes:
            raise AssertionError("Original application/index changed during the probes")
        report.update(completed=True, fixtureInventoryUnchanged=True, originalHashes=original_hashes,
            originalHashesAfter=final_hashes, sidecars=sidecars, childPath=env["PATH"], workingDirectory=str(windows))
        print("PASS portable transfer: Unicode/spaces and >340 paths, restricted PATH, original EXE roots, Russian/Kazakh search, 599-document graph, missing/corrupt data and no extraction")
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        report["limitations"] = ["Runs on the current Windows installation, not a clean VM",
            "PATH and Java/Python environment isolation do not emulate every absent installed program",
            "File snapshots cannot detect transient create/delete operations or registry writes; absence of such operations remains unverified",
            "External viewers are not launched; documents are intentionally absent in copied fixtures",
            "Model/weights are absent; real inference quality, memory, timing and GPU remain untested",
            "Windows, security software and drivers can create their own records outside the fixture inventory"]
        report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

if __name__ == "__main__":
    main()
