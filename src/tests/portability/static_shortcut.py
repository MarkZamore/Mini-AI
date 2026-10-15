"""Observer-created Windows shortcut launching the actual static SWT EXE."""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
from static_pe import assert_static_imports, assert_static_modules, process_modules

class SHELLEXECUTEINFO(C.Structure):
    _fields_=[('cbSize',W.DWORD),('fMask',W.ULONG),('hwnd',W.HWND),('lpVerb',W.LPCWSTR),('lpFile',W.LPCWSTR),('lpParameters',W.LPCWSTR),('lpDirectory',W.LPCWSTR),('nShow',C.c_int),('hInstApp',W.HINSTANCE),('lpIDList',C.c_void_p),('lpClass',W.LPCWSTR),('hkeyClass',W.HKEY),('dwHotKey',W.DWORD),('hIcon',W.HANDLE),('hProcess',W.HANDLE)]

class GUID(C.Structure):
    _fields_=[('data1',W.DWORD),('data2',W.WORD),('data3',W.WORD),('data4',C.c_ubyte*8)]
    @staticmethod
    def parse(value):return GUID.from_buffer_copy(uuid.UUID(value).bytes_le)

def create_shortcut(shortcut,exe,working):
    # IShellLinkW avoids the ANSI filesystem conversion in WScript.Shell on
    # some Windows builds. This is an observer-only COM object, never app code.
    ole=C.WinDLL('ole32');ole.CoInitializeEx.argtypes=[C.c_void_p,W.DWORD];ole.CoInitializeEx.restype=C.c_long
    ole.CoCreateInstance.argtypes=[C.POINTER(GUID),C.c_void_p,W.DWORD,C.POINTER(GUID),C.POINTER(C.c_void_p)];ole.CoCreateInstance.restype=C.c_long
    ole.CoUninitialize.argtypes=[]
    link=C.c_void_p();persist=C.c_void_p()
    def invoke(obj,index,types,*args):
        address=C.cast(obj,C.POINTER(C.POINTER(C.c_void_p))).contents[index]
        result=C.WINFUNCTYPE(C.c_long,C.c_void_p,*types)(address)(obj,*args)
        if result<0:raise OSError(result,'Observer ShellLink COM call '+str(index))
    initialized=ole.CoInitializeEx(None,2)
    if initialized<0:raise OSError(initialized,'Observer CoInitializeEx')
    try:
        clsid=GUID.parse('00021401-0000-0000-c000-000000000046');iid=GUID.parse('000214f9-0000-0000-c000-000000000046')
        code=ole.CoCreateInstance(C.byref(clsid),None,1,C.byref(iid),C.byref(link))
        if code<0:raise OSError(code,'Observer CoCreateInstance ShellLink')
        invoke(link,20,[W.LPCWSTR],str(exe));invoke(link,11,[W.LPCWSTR],'--ui-smoke');invoke(link,9,[W.LPCWSTR],str(working))
        iid=GUID.parse('0000010b-0000-0000-c000-000000000046')
        invoke(link,0,[C.POINTER(GUID),C.POINTER(C.c_void_p)],C.byref(iid),C.byref(persist))
        invoke(persist,6,[W.LPCWSTR,W.BOOL],str(shortcut),True)
    finally:
        if persist:invoke(persist,2,[])
        if link:invoke(link,2,[])
        ole.CoUninitialize()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--exe',type=Path,required=True);parser.add_argument("--report-prefix",default="dusty-yellow");args=parser.parse_args()
    exe=args.exe.resolve();imports=assert_static_imports(exe)
    build=Path(__file__).resolve().parents[2]/'build/test-results'
    prefix=args.report_prefix
    if not prefix.isascii() or not prefix.replace('-', '').isalnum():raise ValueError('Invalid report prefix')
    folder=build/(prefix+'-shortcut')/uuid.uuid4().hex;folder.mkdir(parents=True)
    shortcut=folder/(exe.stem+' перенос Қазақ.lnk');working=folder/'другая рабочая папка';working.mkdir()
    create_shortcut(shortcut,exe,working)
    if not shortcut.exists():raise AssertionError('Observer could not create shortcut')
    shell=C.WinDLL('shell32',use_last_error=True);kernel=C.WinDLL('kernel32',use_last_error=True)
    shell.ShellExecuteExW.argtypes=[C.POINTER(SHELLEXECUTEINFO)];shell.ShellExecuteExW.restype=W.BOOL
    kernel.WaitForSingleObject.argtypes=[W.HANDLE,W.DWORD];kernel.WaitForSingleObject.restype=W.DWORD
    kernel.GetExitCodeProcess.argtypes=[W.HANDLE,C.POINTER(W.DWORD)];kernel.GetExitCodeProcess.restype=W.BOOL
    kernel.CloseHandle.argtypes=[W.HANDLE];kernel.CloseHandle.restype=W.BOOL
    info=SHELLEXECUTEINFO();info.cbSize=C.sizeof(info);info.fMask=0x40|0x100|0x400;info.lpVerb='open';info.lpFile=str(shortcut);info.lpDirectory=str(working);info.nShow=1
    if not shell.ShellExecuteExW(C.byref(info)) or not info.hProcess:raise OSError(C.get_last_error(),'Shortcut launch did not return its process handle')
    try:
        modules=set()
        for attempt in range(30):
            modules.update(process_modules(info.hProcess))
            if kernel.WaitForSingleObject(info.hProcess,40)==0:break
        if kernel.WaitForSingleObject(info.hProcess,20000)!=0:raise AssertionError('Shortcut UI smoke timed out')
        code=W.DWORD()
        if not kernel.GetExitCodeProcess(info.hProcess,C.byref(code)) or code.value:raise AssertionError('Shortcut UI smoke failed: '+str(code.value))
        assert_static_modules(exe,modules)
    finally:kernel.CloseHandle(info.hProcess)
    with exe.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
    report={'success':True,'exe':str(exe),'exeSha256':digest,'shortcut':str(shortcut),'workingDirectory':str(working),'arguments':['--ui-smoke'],'exitCode':code.value,'imports':imports,'loadedModules':sorted(modules),'limitations':['Shortcut is created by the observer; its writes are excluded from the application no-write guarantee.','Actual GUI startup/exit and loaded executable are verified; Shell/Windows filesystem and registry operations are outside this test evidence.']}
    (build/(prefix+'-shortcut.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS actual Windows shortcut, different working directory, static SWT startup/exit and OS-only loaded modules')

if __name__=='__main__':main()
