"""Observe the real static SWT licence/source dialog; writes only observer reports."""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone

parser = argparse.ArgumentParser()
parser.add_argument('--exe', required=True)
args = parser.parse_args()
exe = Path(args.exe).resolve()
root = Path(__file__).resolve().parents[2]
user = C.WinDLL('user32', use_last_error=True)
callback = C.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)
user.GetWindowThreadProcessId.argtypes = [W.HWND, C.POINTER(W.DWORD)]
user.GetWindowTextLengthW.argtypes = [W.HWND]
user.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, C.c_int]
user.GetMenu.argtypes = [W.HWND]; user.GetMenu.restype = W.HMENU
user.GetSubMenu.argtypes = [W.HMENU, C.c_int]; user.GetSubMenu.restype = W.HMENU
user.GetMenuItemID.argtypes = [W.HMENU, C.c_int]; user.GetMenuItemID.restype = W.UINT
user.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]
user.SendMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]; user.SendMessageW.restype = W.LPARAM
user.EnumWindows.argtypes = [callback, W.LPARAM]
user.EnumChildWindows.argtypes = [W.HWND, callback, W.LPARAM]
user.GetClassNameW.argtypes = [W.HWND, W.LPWSTR, C.c_int]


def title(hwnd):
    # WM_GETTEXT is marshalled by Windows and reads controls in another process.
    size = user.SendMessageW(hwnd, 0x0e, 0, 0)
    if size < 0 or size > 2_000_000:
        raise AssertionError('Unexpected control text size')
    buffer = C.create_unicode_buffer(size + 1)
    user.SendMessageW(hwnd, 0x0d, len(buffer), C.addressof(buffer))
    return buffer.value


def windows(pid):
    values = []
    @callback
    def visit(hwnd, unused):
        owner = W.DWORD()
        user.GetWindowThreadProcessId(hwnd, C.byref(owner))
        if owner.value == pid:
            values.append(hwnd)
        return True
    user.EnumWindows(visit, 0)
    return values


def children(hwnd):
    values = []
    @callback
    def visit(child, unused):
        buffer = C.create_unicode_buffer(256)
        user.GetClassNameW(child, buffer, len(buffer))
        values.append((child, buffer.value, title(child)))
        return True
    user.EnumChildWindows(hwnd, visit, 0)
    return values


started = datetime.now(timezone.utc).isoformat()
child = subprocess.Popen([str(exe), '--ui-smoke'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
try:
    deadline = time.monotonic() + 4
    main = None
    while time.monotonic() < deadline:
        main = next((h for h in windows(child.pid) if title(h).startswith('EnterpriseAI — документы')), None)
        if main:
            break
        time.sleep(.05)
    if not main:
        raise AssertionError('Main SWT window missing')
    menu = user.GetMenu(main)
    submenu = user.GetSubMenu(menu, 0)
    command = user.GetMenuItemID(submenu, 0)
    if command == 0xffffffff:
        raise AssertionError('Embedded licences menu command missing')
    user.PostMessageW(main, 0x111, command, 0)
    dialog = None
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        dialog = next((h for h in windows(child.pid) if 'лицензии и исходники' in title(h)), None)
        if dialog:
            break
        time.sleep(.05)
    if not dialog:
        raise AssertionError('Legal dialog missing')
    values = children(dialog)
    notice = next((text for _, _, text in values if 'GNU General Public License' in text), '')
    if not notice or 'Microsoft' not in notice:
        raise AssertionError('Embedded legal text is not shown')
    tab = next((h for h, name, _ in values if name == 'SysTabControl32'), None)
    if not tab:
        raise AssertionError('Source tab control missing')
    # Native tab handles the key and creates its notification inside the app.
    # Never pass a process-local NMHDR pointer to another process.
    user.SendMessageW(tab, 0x100, 0x27, 0)  # WM_KEYDOWN / VK_RIGHT
    user.SendMessageW(tab, 0x101, 0x27, 0)  # WM_KEYUP
    time.sleep(.2)
    values = children(dialog)
    source = next((text for _, _, text in values if 'NativeLibrarySupport' in text and len(text) > 10000), '')
    if not source:
        raise AssertionError('Source changes were not loaded into the real text control')
    user.PostMessageW(dialog, 0x10, 0, 0)
    output, error = child.communicate(timeout=10)
    if child.returncode or error:
        raise AssertionError(f'Legal UI exit failed: {error!r}')
    report = {'success': True, 'exeSha256': hashlib.sha256(exe.read_bytes()).hexdigest(),
        'pid': child.pid, 'exe': str(exe), 'startedUtc': started,
        'completedUtc': datetime.now(timezone.utc).isoformat(), 'exitCode': child.returncode,
        'noticesCharacters': len(notice), 'sourceCharacters': len(source), 'realSwtControls': True,
        'observerGeneratedNotifications': True, 'stdout': output.decode('utf-8'),
        'limitations': ['ProcMon supplies the filesystem/registry evidence; this test proves displayed in-memory content.']}
    (root / 'build/test-results/static-legal-ui.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'success': True, 'noticesCharacters': len(notice), 'sourceCharacters': len(source)}))
finally:
    if child.poll() is None:
        child.kill()
        child.communicate()
