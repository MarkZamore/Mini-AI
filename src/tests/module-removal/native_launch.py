"""Developer observer: explicit CreateProcess application path, UTF-8 pipes and app-local CRT proof."""
from __future__ import annotations
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import subprocess
import struct
import sys
import time

def extended(path: str) -> str:
    path = os.path.abspath(path)
    if len(path) < 248 or path.startswith("\\\\?\\"):
        return path
    return "\\\\?\\UNC\\" + path[2:] if path.startswith("\\\\") else "\\\\?\\" + path

def canonical(path: str) -> str:
    if path.startswith("\\\\?\\UNC\\"):
        path = "\\\\" + path[8:]
    elif path.startswith("\\\\?\\"):
        path = path[4:]
    return os.path.normcase(os.path.normpath(path))

def imported_crt(exe: str) -> set[str]:
    data = Path(extended(exe)).read_bytes()
    pe = struct.unpack_from("<I", data, 0x3c)[0]
    sections, optional_bytes = struct.unpack_from("<H", data, pe+6)[0], struct.unpack_from("<H", data, pe+20)[0]
    optional = pe+24
    directory = optional+(112 if struct.unpack_from("<H", data, optional)[0] == 0x20b else 96)
    import_rva = struct.unpack_from("<I", data, directory+8)[0]
    def offset(rva: int) -> int:
        for index in range(sections):
            row = optional+optional_bytes+index*40
            virtual_bytes, virtual_address, raw_bytes, raw_offset = struct.unpack_from("<IIII", data, row+8)
            if virtual_address <= rva < virtual_address+max(virtual_bytes, raw_bytes):
                return raw_offset+rva-virtual_address
        raise AssertionError("Invalid PE import address")
    imported = set(); row = offset(import_rva)
    while any(data[row:row+20]):
        name_offset = offset(struct.unpack_from("<I", data, row+12)[0])
        name = data[name_offset:data.index(b"\0", name_offset)].decode("ascii").lower()
        if name in ("vcruntime140.dll", "vcruntime140_1.dll"):
            imported.add(name)
        row += 20
    return imported

def main() -> None:
    exe, command, timeout, expected_code, cwd = sys.argv[1:]
    env = dict(os.environ)
    for name in ("JAVA_HOME", "PYTHONHOME", "PYTHONPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(name, None)
    windows = os.environ["SYSTEMROOT"]
    env["PATH"] = str(Path(windows) / "System32") + os.pathsep + windows
    # Supplying executable sets lpApplicationName explicitly; a null application name
    # imposes MAX_PATH on the first command-line token even with an extended path.
    child = subprocess.Popen([extended(exe), "--" + command], executable=extended(exe), cwd=extended(cwd), env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="strict")
    loaded_crt = {}
    expected_crt = imported_crt(exe) if command == "ui-smoke" else set()
    try:
        if command == "ui-smoke":
            psapi = ctypes.WinDLL("psapi", use_last_error=True)
            psapi.EnumProcessModulesEx.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE), wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), wintypes.DWORD]
            psapi.EnumProcessModulesEx.restype = wintypes.BOOL
            psapi.GetModuleFileNameExW.argtypes = [wintypes.HANDLE, wintypes.HMODULE, wintypes.LPWSTR, wintypes.DWORD]
            psapi.GetModuleFileNameExW.restype = wintypes.DWORD
            for _ in range(25):
                modules = (wintypes.HMODULE * 1024)(); size = wintypes.DWORD()
                if psapi.EnumProcessModulesEx(wintypes.HANDLE(int(child._handle)), modules, ctypes.sizeof(modules), ctypes.byref(size), 3):
                    for module in modules[:size.value // ctypes.sizeof(wintypes.HMODULE)]:
                        path = ctypes.create_unicode_buffer(32768)
                        if psapi.GetModuleFileNameExW(wintypes.HANDLE(int(child._handle)), module, path, len(path)):
                            name = os.path.basename(path.value).lower()
                            if name in ("vcruntime140.dll", "vcruntime140_1.dll"):
                                loaded_crt[name] = path.value
                if expected_crt.issubset(loaded_crt): break
                time.sleep(.05)
        stdout, stderr = child.communicate(timeout=float(timeout))
        if child.returncode != int(expected_code) or stderr:
            raise AssertionError(f"Native {command} exit {child.returncode}: {stderr} {stdout}")
        payload = json.loads(stdout)
        if command == "ui-smoke":
            for name in expected_crt | loaded_crt.keys():
                if canonical(loaded_crt.get(name, "")) != canonical(str(Path(exe).parent / name)):
                    raise AssertionError("CRT did not load beside the variant EXE: " + name)
            payload["observerLoadedRuntime"] = loaded_crt
            payload["observerRequiredRuntime"] = sorted(expected_crt)
        sys.stdout.reconfigure(encoding="utf-8")
        print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    finally:
        if child.poll() is None:
            child.kill(); child.wait()

if __name__ == "__main__":
    main()
