"""Observer-only fixtures and reports stay in src/build; the probe only reads."""
from pathlib import Path
from ctypes import wintypes
import base64
import ctypes
import hashlib
import json
import os
import shutil
import subprocess
import time

root = Path(__file__).resolve().parents[1]
gate = root / "build/static-toolchain/runtime-gate"
fixture = gate / "acceptance"
fixture.mkdir(exist_ok=True)
image = gate / "StaticRuntimeProbe.exe"
windows = Path(os.environ["SYSTEMROOT"])
allowed = {"version.dll", "advapi32.dll", "ws2_32.dll", "userenv.dll", "user32.dll", "kernel32.dll",
    "ncrypt.dll", "crypt32.dll", "iphlpapi.dll", "mswsock.dll", "ntdll.dll", "kernelbase.dll",
    "sechost.dll", "rpcrt4.dll", "win32u.dll", "gdi32.dll", "gdi32full.dll", "msvcp_win.dll",
    "ucrtbase.dll", "bcrypt.dll", "bcryptprimitives.dll", "ncryptsslp.dll", "cryptbase.dll",
    "sspicli.dll", "profapi.dll", "imm32.dll", "msasn1.dll", "rsaenh.dll", "cryptsp.dll",
    "dpapi.dll", "nsi.dll", "dhcpcsvc6.dll", "dhcpcsvc.dll", "dnsapi.dll", "winnsi.dll",
    "apphelp.dll", "msvcrt.dll", "ntasn1.dll", "kernel.appcore.dll"}


def extended(path):
    return "\\\\?\\" + str(path.resolve())


def inventory():
    result = {}
    base = extended(fixture)
    for folder, _, names in os.walk(base):
        for name in names:
            path = Path(folder) / name
            result[os.path.relpath(path, base)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def modules(process):
    api = ctypes.WinDLL("psapi", use_last_error=True)
    api.EnumProcessModulesEx.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE), wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD), wintypes.DWORD]
    api.EnumProcessModulesEx.restype = wintypes.BOOL
    api.GetModuleFileNameExW.argtypes = [wintypes.HANDLE, wintypes.HMODULE, wintypes.LPWSTR, wintypes.DWORD]
    array = (wintypes.HMODULE * 512)()
    needed = wintypes.DWORD()
    if not api.EnumProcessModulesEx(process._handle, array, ctypes.sizeof(array), ctypes.byref(needed), 3):
        raise ctypes.WinError(ctypes.get_last_error())
    result = []
    for handle in array[:needed.value // ctypes.sizeof(wintypes.HMODULE)]:
        path = ctypes.create_unicode_buffer(32768)
        if not api.GetModuleFileNameExW(process._handle, handle, path, len(path)):
            raise ctypes.WinError(ctypes.get_last_error())
        result.append(path.value)
    return result


env = dict(os.environ)
for variable in ("JAVA_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "PYTHONPATH"):
    env.pop(variable, None)
env["PATH"] = str(windows / "System32") + os.pathsep + str(windows)
temp = fixture / "observer-temp"
temp.mkdir(exist_ok=True)
env["TEMP"] = env["TMP"] = str(temp)
data = fixture / "Исходник Қазақша 🧠.txt"
data.write_bytes("Тест чтения без записи. Қазақша.".encode("utf-8"))
expected = hashlib.sha256(data.read_bytes()).hexdigest()
short = fixture / "Кириллица Қазақша" / "StaticRuntimeProbe.exe"
long = fixture.joinpath(*(["Длинная папка Қазақша 0123456789"] * 7), "StaticRuntimeProbe.exe")
for exe in (short, long):
    Path(extended(exe.parent)).mkdir(parents=True, exist_ok=True)
    shutil.copyfile(image, extended(exe))
before = inventory()
results = []
for exe in (short, long):
    argument = "base64:" + base64.b64encode(str(data).encode("utf-8")).decode("ascii")
    child = subprocess.Popen([extended(exe), argument, "--hold"], executable=extended(exe), cwd=windows,
        env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        ready = child.stdout.readline().decode("utf-8")
        if expected not in ready:
            raise AssertionError(f"Incorrect runtime probe output: {ready!r}")
        loaded = modules(child)
        unexpected = [p for p in loaded if Path(p).suffix.lower() == ".dll"
            and (Path(p).name.lower() not in allowed or not Path(p).is_relative_to(windows))]
        if unexpected:
            raise AssertionError(f"Non-inbox modules: {unexpected}")
        output, error = child.communicate(b"x", timeout=10)
        if child.returncode:
            raise AssertionError(error.decode("utf-8", errors="replace"))
        results.append({"mode": "hold", "exePathLength": len(str(exe)), "exitCode": child.returncode,
            "loadedModules": loaded, "output": ready})
    finally:
        if child.poll() is None:
            child.kill()
            child.communicate()
for mode in ("--fatal", "--oom"):
    child = subprocess.run([extended(short),
        "base64:" + base64.b64encode(str(data).encode("utf-8")).decode("ascii"), mode],
        cwd=windows, env=env, capture_output=True, timeout=30)
    if child.returncode == 0:
        raise AssertionError(f"Failure probe {mode} returned success")
    results.append({"mode": mode, "exitCode": child.returncode,
        "stderr": child.stderr.decode("utf-8", errors="replace")[:4096]})
after = inventory()
if before != after:
    raise AssertionError("Runtime modified fixture files")
report = {"completed": True, "exeSha256": hashlib.sha256(image.read_bytes()).hexdigest(),
    "cases": results, "fixtureFilesUnchanged": before == after,
    "limitations": ["Filesystem hash comparison is not a ProcMon trace and does not detect denied writes or registry changes",
        "UTF-16 Windows argv is tested in the platform JNI gate, not by this base64 NIO probe"]}
(gate / "acceptance.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"completed": True, "cases": len(results), "fixtureFilesUnchanged": True}))
