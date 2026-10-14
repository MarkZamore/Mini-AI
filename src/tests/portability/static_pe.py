"""Developer observer for PE32+ static-profile imports and process modules.

No application code depends on this file. Imports include delay imports, not
only the usual import table. Runtime snapshots are evidence for exercised paths,
not a proof about unexecuted dynamic-load branches.
"""
from __future__ import annotations
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import struct

SYSTEM_IMPORTS = {
    'advapi32.dll', 'bcrypt.dll', 'bcryptprimitives.dll', 'comctl32.dll',
    'comdlg32.dll', 'crypt32.dll', 'dwmapi.dll', 'gdi32.dll', 'gdiplus.dll',
    'imm32.dll', 'iphlpapi.dll', 'kernel32.dll', 'msimg32.dll', 'msvcrt.dll', 'msvcp_win.dll',
    'mswsock.dll', 'ncrypt.dll', 'netapi32.dll', 'ntdll.dll', 'ole32.dll', 'oleacc.dll',
    'oleaut32.dll', 'opengl32.dll', 'powrprof.dll', 'propsys.dll', 'psapi.dll', 'rpcrt4.dll',
    'secur32.dll', 'setupapi.dll', 'shell32.dll', 'shlwapi.dll', 'ucrtbase.dll',
    'urlmon.dll', 'user32.dll', 'userenv.dll', 'usp10.dll', 'uxtheme.dll',
    'version.dll', 'winhttp.dll', 'wininet.dll', 'winmm.dll', 'winspool.drv',
    'ws2_32.dll', 'wtsapi32.dll',
}

def canonical(value):
    value = str(value)
    if value.startswith('\\\\?\\UNC\\'): value = '\\\\' + value[8:]
    elif value.startswith('\\\\?\\'): value = value[4:]
    return os.path.normcase(os.path.abspath(value))

def system_import(name):
    name = name.lower()
    return name in SYSTEM_IMPORTS or name.startswith(('api-ms-win-', 'ext-ms-win-'))

def extended(path):
    value = str(Path(path).absolute())
    if len(value) < 248 or value.startswith('\\\\?\\'): return value
    return '\\\\?\\UNC\\'+value[2:] if value.startswith('\\\\') else '\\\\?\\'+value

def pe_imports(path: Path):
    data = Path(extended(path)).read_bytes()
    if len(data) < 64 or data[:2] != b'MZ': raise AssertionError('Not a PE executable')
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    if data[pe:pe+4] != b'PE\0\0': raise AssertionError('Invalid PE signature')
    machine, sections = struct.unpack_from('<HH', data, pe+4)
    optional_size = struct.unpack_from('<H', data, pe+20)[0]
    optional = pe+24
    if machine != 0x8664 or struct.unpack_from('<H', data, optional)[0] != 0x20b:
        raise AssertionError('Static profile requires Windows x64 PE32+')
    image_base = struct.unpack_from('<Q', data, optional+24)[0]
    headers_size = struct.unpack_from('<I', data, optional+60)[0]
    directories = min(struct.unpack_from('<I', data, optional+108)[0], (optional_size-112)//8)
    def offset(rva):
        if rva < headers_size and rva < len(data): return rva
        for index in range(sections):
            row = optional+optional_size+index*40
            virtual_size, address, raw_size, raw_offset = struct.unpack_from('<IIII', data, row+8)
            if address <= rva < address+max(virtual_size, raw_size):
                value = raw_offset+rva-address
                if rva-address >= raw_size or value >= len(data): break
                return value
        raise AssertionError(f'Invalid PE RVA 0x{rva:x}')
    def name_at(rva):
        start = offset(rva)
        end = data.find(b'\0', start, min(start+4096, len(data)))
        if end < 0: raise AssertionError('Unterminated PE import name')
        return data[start:end].decode('ascii').lower()
    result = {'machine': 'x64', 'ordinary': [], 'delay': []}
    for key, index, width in [('ordinary', 1, 20), ('delay', 13, 32)]:
        if index >= directories: continue
        rva, size = struct.unpack_from('<II', data, optional+112+index*8)
        if not rva: continue
        table = offset(rva)
        # A table terminates in a zero descriptor. Respect directory size and
        # reject malformed descriptors rather than silently skipping imports.
        for displacement in range(0, size, width):
            row = table+displacement
            descriptor = data[row:row+width]
            if len(descriptor) != width: raise AssertionError('Truncated PE import table')
            if not any(descriptor): break
            if key == 'ordinary': name_rva = struct.unpack_from('<I', data, row+12)[0]
            else:
                attributes, name_rva = struct.unpack_from('<II', data, row)
                if not attributes & 1: name_rva -= image_base
            result[key].append(name_at(name_rva))
        else: raise AssertionError('PE import table missing terminator')
    return result

def assert_static_imports(path):
    result = pe_imports(Path(path))
    invalid = sorted({name for names in (result['ordinary'], result['delay']) for name in names if not system_import(name)})
    if invalid: raise AssertionError('Non-Windows PE dependencies: ' + ', '.join(invalid))
    return result

def process_modules(handle):
    psapi = ctypes.WinDLL('psapi', use_last_error=True)
    psapi.EnumProcessModulesEx.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE), wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), wintypes.DWORD]
    psapi.EnumProcessModulesEx.restype = wintypes.BOOL
    psapi.GetModuleFileNameExW.argtypes = [wintypes.HANDLE, wintypes.HMODULE, wintypes.LPWSTR, wintypes.DWORD]
    psapi.GetModuleFileNameExW.restype = wintypes.DWORD
    handles = (wintypes.HMODULE*2048)(); needed = wintypes.DWORD()
    if not psapi.EnumProcessModulesEx(wintypes.HANDLE(int(handle)), handles, ctypes.sizeof(handles), ctypes.byref(needed), 3):
        return []
    if needed.value > ctypes.sizeof(handles): raise AssertionError('Module snapshot buffer too small')
    result = []
    for module in handles[:needed.value//ctypes.sizeof(wintypes.HMODULE)]:
        path = ctypes.create_unicode_buffer(32768)
        if psapi.GetModuleFileNameExW(wintypes.HANDLE(int(handle)), module, path, len(path)):
            result.append(path.value)
    return result

def assert_static_modules(exe, modules):
    windows = canonical(os.environ['SYSTEMROOT'])
    allowed_roots = [windows+'\\system32\\', windows+'\\winsxs\\']
    forbidden = ('vcruntime', 'msvcp', 'jvm.', 'java.', 'swt-', 'enterpriseai-', 'sqlite', 'libomp', 'libiomp', 'cudart', 'sycl')
    invalid = []
    for path in modules:
        normalized = canonical(path); name = os.path.basename(normalized).lower()
        if normalized == canonical(exe): continue
        banned = name.startswith(forbidden) and name != 'msvcp_win.dll'
        if banned or not any(normalized.startswith(root) for root in allowed_roots): invalid.append(path)
    if invalid: raise AssertionError('Non-system loaded modules: ' + ', '.join(invalid))
    if canonical(exe) not in {canonical(path) for path in modules}:
        raise AssertionError('No actual executable in process module snapshots')
