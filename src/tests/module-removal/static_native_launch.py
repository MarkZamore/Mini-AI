"""Observer for a static native composition; no app-local runtime is copied."""
import json
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'portability'))
from static_pe import assert_static_imports, assert_static_modules, process_modules
from portable_transfer import extended

def dismiss_owned_errors(pid, seen):
    """Close only this process's visible, owned branded modal shells."""
    user32=ctypes.WinDLL('user32',use_last_error=True)
    callback_type=ctypes.WINFUNCTYPE(wintypes.BOOL,wintypes.HWND,wintypes.LPARAM)
    user32.EnumWindows.argtypes=[callback_type,wintypes.LPARAM]
    user32.GetWindowThreadProcessId.argtypes=[wintypes.HWND,ctypes.POINTER(wintypes.DWORD)]
    user32.GetWindow.argtypes=[wintypes.HWND,wintypes.UINT];user32.GetWindow.restype=wintypes.HWND
    user32.GetWindowTextW.argtypes=[wintypes.HWND,wintypes.LPWSTR,ctypes.c_int]
    user32.IsWindowVisible.argtypes=[wintypes.HWND]
    user32.PostMessageW.argtypes=[wintypes.HWND,wintypes.UINT,wintypes.WPARAM,wintypes.LPARAM]
    def inspect(hwnd, unused):
        process=wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd,ctypes.byref(process))
        owner=user32.GetWindow(hwnd,4)
        if process.value!=pid or not owner or not user32.IsWindowVisible(hwnd):return True
        owner_process=wintypes.DWORD();user32.GetWindowThreadProcessId(owner,ctypes.byref(owner_process))
        title=ctypes.create_unicode_buffer(1024);user32.GetWindowTextW(hwnd,title,len(title))
        if owner_process.value==pid and title.value=='миниИИ' and int(hwnd) not in seen:
            seen.add(int(hwnd));user32.PostMessageW(hwnd,0x0010,0,0)
        return True
    user32.EnumWindows(callback_type(inspect),0)

def main():
    exe, command, timeout, expected, cwd = sys.argv[1:]
    exe = Path(exe).absolute()
    imports = assert_static_imports(exe)
    env = dict(os.environ)
    for name in ('JAVA_HOME', 'PYTHONHOME', 'PYTHONPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(name, None)
    env['PATH'] = os.path.join(env['SYSTEMROOT'], 'System32') + os.pathsep + env['SYSTEMROOT']
    child = subprocess.Popen([extended(exe), '--' + command], executable=extended(exe), cwd=cwd, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='strict')
    try:
        modules = set();dismissed=set()
        if command == 'ui-smoke':
            deadline=time.monotonic()+float(timeout)
            while child.poll() is None and time.monotonic()<deadline:
                modules.update(process_modules(child._handle))
                dismiss_owned_errors(child.pid,dismissed)
                time.sleep(.04)
        stdout, stderr = child.communicate(timeout=float(timeout))
        if child.returncode != int(expected) or stderr:
            raise AssertionError(f'Static {command}: exit={child.returncode}; stderr={stderr}; stdout={stdout}')
        payload = json.loads(stdout)
        if command == 'ui-smoke':
            assert_static_modules(exe, modules)
            payload['observerLoadedModules'] = sorted(modules)
            payload['observerPeImports'] = imports
            payload['observerOwnedModalErrorsDismissed'] = len(dismissed)
        sys.stdout.reconfigure(encoding='utf-8')
        print(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
    finally:
        if child.poll() is None: child.kill(); child.wait()

if __name__ == '__main__': main()
