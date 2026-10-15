"""Owned native-window keyboard/Assistant observer; not application code."""
import argparse, ctypes as C, hashlib, importlib.util, json, os, subprocess, time
from ctypes import wintypes as W
from datetime import datetime, timezone
from pathlib import Path

spec=importlib.util.spec_from_file_location('owned_graph_observer',Path(__file__).parents[1]/'visual/native_graph_observer.py')
ui=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui)

class ProcessEntry(C.Structure):
    _fields_=[('dwSize',W.DWORD),('cntUsage',W.DWORD),('th32ProcessID',W.DWORD),('th32DefaultHeapID',C.c_size_t),('th32ModuleID',W.DWORD),('cntThreads',W.DWORD),('th32ParentProcessID',W.DWORD),('pcPriClassBase',W.LONG),('dwFlags',W.DWORD),('szExeFile',W.WCHAR*260)]

def exact_worker(parent,expected):
    kernel=C.WinDLL('kernel32',use_last_error=True)
    kernel.CreateToolhelp32Snapshot.argtypes=[W.DWORD,W.DWORD];kernel.CreateToolhelp32Snapshot.restype=W.HANDLE
    kernel.Process32FirstW.argtypes=[W.HANDLE,C.POINTER(ProcessEntry)];kernel.Process32NextW.argtypes=[W.HANDLE,C.POINTER(ProcessEntry)]
    kernel.OpenProcess.argtypes=[W.DWORD,W.BOOL,W.DWORD];kernel.OpenProcess.restype=W.HANDLE
    kernel.QueryFullProcessImageNameW.argtypes=[W.HANDLE,W.DWORD,W.LPWSTR,C.POINTER(W.DWORD)]
    kernel.CloseHandle.argtypes=[W.HANDLE]
    snapshot=kernel.CreateToolhelp32Snapshot(2,0);entry=ProcessEntry();entry.dwSize=C.sizeof(entry);matches=[]
    if snapshot==C.c_void_p(-1).value:raise OSError(C.get_last_error(),'Process snapshot failed')
    try:
        valid=kernel.Process32FirstW(snapshot,C.byref(entry))
        while valid:
            if entry.th32ParentProcessID==parent:
                handle=kernel.OpenProcess(0x100000|0x1000,False,entry.th32ProcessID)
                if handle:
                    name=C.create_unicode_buffer(32768);length=W.DWORD(len(name))
                    if kernel.QueryFullProcessImageNameW(handle,0,name,C.byref(length)) and os.path.normcase(name.value)==os.path.normcase(str(expected)):
                        matches.append((entry.th32ProcessID,handle))
                    else:kernel.CloseHandle(handle)
            valid=kernel.Process32NextW(snapshot,C.byref(entry))
    finally:kernel.CloseHandle(snapshot)
    if len(matches)>1:
        for _,handle in matches:kernel.CloseHandle(handle)
        raise AssertionError('Worker process identity is ambiguous')
    return (kernel,matches[0]) if matches else (kernel,None)

def stop_cancel(top,editor,process,exe,output,report):
    ui.u.GetParent.argtypes=[W.HWND];ui.u.GetParent.restype=W.HWND
    ui.u.GetDpiForWindow.argtypes=[W.HWND];ui.u.GetDpiForWindow.restype=W.UINT
    composer=ui.u.GetParent(editor);dpi=ui.u.GetDpiForWindow(top);expected=round(40*dpi/96)
    circles=[h for h in ui.children(composer,process.pid) if ui.u.IsWindowVisible(h) and abs((ui.rect(h).right-ui.rect(h).left)-expected)<=2 and abs((ui.rect(h).bottom-ui.rect(h).top)-expected)<=2 and ui.classname(h).startswith('SWT')]
    if len(circles)!=1:raise AssertionError('One owned 40DIP composer circle required; actual '+str(len(circles)))
    circle=circles[0];initial=ui.rect(circle);bounds=[initial.left,initial.top,initial.right,initial.bottom]
    def icon(stage):return ui.capture(circle,process.pid,output.with_name(output.stem+'-'+stage+'.png'))
    idle=icon('send-idle');report.update(singleSendStopControl=True,separateCancelAbsent=True,sendCircleHwnd=int(circle),sendCirclePhysicalBounds=bounds,actualNativeWindowDpi=dpi)
    value=C.create_unicode_buffer('Академическая политика');ui.send(editor,process.pid,0xC,0,C.addressof(value));ui.key_input(top,process.pid,0x0D)
    expected_worker=exe.parent/'model/inference-worker.exe';deadline=time.monotonic()+5;identity=None
    while time.monotonic()<deadline and process.poll() is None:
        kernel,identity=exact_worker(process.pid,expected_worker)
        if identity:break
        if ui.dismiss_owned_dialogs(process.pid):raise AssertionError('Unexpected error while starting stop-cancel request')
        time.sleep(.03)
    if not identity:raise AssertionError('Exact controlled hanging worker did not start')
    worker_pid,handle=identity
    kernel.WaitForSingleObject.argtypes=[W.HANDLE,W.DWORD];kernel.WaitForSingleObject.restype=W.DWORD
    try:
        # Waiting keeps the same process handle: a reused numeric PID cannot prove exit.
        time.sleep(.3)
        if kernel.WaitForSingleObject(handle,0)!=0x102:raise AssertionError('Test worker must remain blocked before Stop')
        busy=icon('stop-busy');current=ui.rect(circle)
        if [current.left,current.top,current.right,current.bottom]!=bounds:raise AssertionError('Send/Stop circle changed bounds while busy')
        ui.u.GetSysColor.argtypes=[C.c_int];ui.u.GetSysColor.restype=W.DWORD
        color=ui.u.GetSysColor(14);foreground=(color&255,(color>>8)&255,(color>>16)&255)
        half=max(2,round(4*dpi/96));centre=(busy.width//2,busy.height//2)
        region=(centre[0]-half,centre[1]-half,centre[0]+half,centre[1]+half)
        def filled(image):
            pixels=list(image.crop(region).getdata());return sum(p==foreground for p in pixels)/len(pixels)
        if filled(busy)<.9 or filled(idle)>=.9:raise AssertionError('Actual busy icon must be a filled centre square, different from idle arrow')
        report.update(nativeStopSquare=True,sendStopStateObserved=True,workerPid=worker_pid,workerParentPid=process.pid,workerPath=str(expected_worker),workerSha256=hashlib.file_digest(expected_worker.open('rb'),'sha256').hexdigest(),stopSquarePixelFraction=filled(busy))
        next_draft=C.create_unicode_buffer('Следующий запрос');ui.send(editor,process.pid,0xC,0,C.addressof(next_draft))
        report['stopForegroundActivationAttempts']=activate_owned(top,process.pid)
        ui.move_owned(circle,top,process.pid,busy.width//2,busy.height//2);cancel_started=time.monotonic();ui.mouse_input(2);ui.mouse_input(4)
        if kernel.WaitForSingleObject(handle,3000)!=0:raise AssertionError('Stop failed to terminate the exact controlled worker within3seconds')
        report['stopWorkerExitSeconds']=time.monotonic()-cancel_started
        deadline=time.monotonic()+2;restored=False;dialogs=[]
        while time.monotonic()<deadline:
            ui.dismiss_owned_dialogs(process.pid,dialogs)
            if dialogs:raise AssertionError('Cancellation must not show an error popup')
            image=icon('send-restored')
            # Cursor hover may alter the circle background. Only the foreground
            # glyph mask is compared; square -> identical arrow must still be proven.
            if [p==foreground for p in image.crop(region).getdata()]==[p==foreground for p in idle.crop(region).getdata()]:restored=True;break
            time.sleep(.04)
        if not restored:raise AssertionError('Shared button did not restore the actual idle arrow after cancellation')
        time.sleep(.3);ui.dismiss_owned_dialogs(process.pid,dialogs)
        if dialogs:raise AssertionError('Cancellation showed a delayed error popup')
        if ui.control_text(editor,process.pid)!=next_draft.value:raise AssertionError('Cancellation lost the next query draft')
        report.update(stopCancelsWorker=True,sendStopCancellationObserved=True,restoredArrow=True,sendArrowRestoredAfterCancellation=True,cancellationErrorDialogs=dialogs,submittedWithEnter=True,inferenceReached=True,nextQueryDraftPreserved=True)
    finally:kernel.CloseHandle(handle)

def activate_owned(top,pid):
    if ui.owner(top)!=pid:raise RuntimeError('Refusing foreground activation of a foreign window')
    deadline=time.monotonic()+2;attempts=[]
    while time.monotonic()<deadline:
        accepted=bool(ui.u.SetForegroundWindow(top))
        foreground=ui.u.GetForegroundWindow()
        attempts.append({'accepted':accepted,'foregroundPid':ui.owner(foreground),'foregroundIsMain':foreground==top})
        if foreground==top:return attempts
        time.sleep(.05)
    raise RuntimeError('Owned main window did not receive foreground within2seconds; no keyboard/mouse input was sent')

def main():
    try:ui.u.SetProcessDpiAwarenessContext(C.c_void_p(-4))
    except (AttributeError,OSError):pass
    parser=argparse.ArgumentParser();parser.add_argument('--exe',required=True)
    parser.add_argument('--scenario',choices=['citation','missing-model','stop-cancel'],default='citation')
    parser.add_argument('--output');args=parser.parse_args()
    exe=Path(args.exe).resolve();build=Path(__file__).resolve().parents[2]/'build'
    output=Path(args.output).resolve() if args.output else build/'test-results'/('dusty-yellow-assistant-'+args.scenario+'.json')
    if not output.is_relative_to(build.resolve()):raise ValueError('Observer reports must be within src/build')
    output.parent.mkdir(parents=True,exist_ok=True)
    started=datetime.now(timezone.utc).isoformat();old_foreground=ui.u.GetForegroundWindow();old_cursor=ui.POINT();ui.u.GetCursorPos(C.byref(old_cursor))
    # Normal launch: --ui-smoke deliberately auto-submits after 800 ms and
    # would contaminate proof that these real keyboard gestures submit once.
    process=subprocess.Popen([str(exe)],cwd=os.environ['SystemRoot'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    report={'success':False,'exe':str(exe),'exeSha256':hashlib.file_digest(exe.open('rb'),'sha256').hexdigest(),'pid':process.pid,'ordinaryParentPid':os.getpid(),'startedUtc':started,'scenario':args.scenario}
    try:
        deadline=time.monotonic()+4;top=None
        while time.monotonic()<deadline and process.poll() is None:
            choices=[h for h in ui.windows(process.pid) if ui.label(h)=='Mini-AI' and not ui.u.GetWindow(h,4)]
            top=max(choices,key=lambda h:(ui.rect(h).right-ui.rect(h).left)*(ui.rect(h).bottom-ui.rect(h).top)) if choices else None
            if top:break
            time.sleep(.03)
        if not top:raise AssertionError('Native Mini-AI main window missing')
        time.sleep(.15);report['startupDialogsDismissed']=ui.dismiss_owned_dialogs(process.pid)
        controls=ui.children(top,process.pid)
        editors=[h for h in controls if ui.classname(h).lower()=='edit' and not ui.u.GetWindowLongPtrW(h,-16)&0x800]
        if len(editors)!=1:raise AssertionError('Writable query editor must be unambiguous')
        editor=editors[0];report['foregroundActivationAttempts']=activate_owned(top,process.pid)
        bounds=ui.rect(editor);ui.move_owned(editor,top,process.pid,min(30,bounds.right-bounds.left-5),min(20,bounds.bottom-bounds.top-5));ui.mouse_input(2);ui.mouse_input(4)
        value=C.create_unicode_buffer('Проверка');ui.send(editor,process.pid,0xC,0,C.addressof(value));ui.send(editor,process.pid,0xB1,len(value.value),len(value.value));ui.key_input(top,process.pid,0x0D,shift=True);time.sleep(.12)
        report['queryAfterShiftEnter']=ui.control_text(editor,process.pid)
        if '\n' not in report['queryAfterShiftEnter']:raise AssertionError('Shift+Enter must insert a newline rather than submit')
        report['shiftEnterNewlineObserved']=True
        if args.scenario=='stop-cancel':
            stop_cancel(top,editor,process,exe,output,report)
            ui.send(top,process.pid,0x10);stdout,stderr=process.communicate(timeout=12)
            if process.returncode or stderr:raise AssertionError('Native UI failed after cancellation: '+repr(stderr))
            report.update(success=True,realNativeUi=True,completedUtc=datetime.now(timezone.utc).isoformat(),exitCode=process.returncode,stdout=stdout.decode('utf-8'),limitations=['Standalone MT hanging mock, not actual model weights.','Actual owned-window input, pixels, exact child image/parent and lifetime process handle verified.','No filesystem or registry conclusion without fresh Process Monitor.'])
            return
        value=C.create_unicode_buffer('Академическая политика');ui.send(editor,process.pid,0xC,0,C.addressof(value))
        ui.key_input(top,process.pid,0x0D);report['submittedWithEnter']=True
        deadline=time.monotonic()+4;messages=[];matched=False;dialogs=[];transcript_error_seen=False
        while time.monotonic()<deadline and process.poll() is None:
            messages.extend(ui.dismiss_owned_dialogs(process.pid,dialogs))
            visible='\n'.join(messages+[ui.control_text(h,process.pid) if ui.classname(h).lower()=='edit' else ui.label(h) for h in ui.children(top,process.pid)])
            modal_text='\n'.join(messages).lower()
            if args.scenario=='citation':
                transcript_error_seen='модель не привела проверяемых ссылок' in visible.lower()
                matched='модель не привела проверяемых ссылок' in modal_text
            else:matched=('модел' in modal_text or 'worker' in modal_text) and any(term in modal_text for term in ['не найден','не подключ','отсутствует'])
            if matched:break
            time.sleep(.04)
        report['transcriptErrorSeen']=transcript_error_seen;report['requestErrorDialogs']=dialogs
        if not matched:raise AssertionError('Expected owned modal error absent (transcript alone is insufficient): '+visible[-1000:])
        # Drain both session-event and future callback paths; they must deduplicate.
        duplicate_deadline=time.monotonic()+.5
        while time.monotonic()<duplicate_deadline and process.poll() is None:
            messages.extend(ui.dismiss_owned_dialogs(process.pid,dialogs));time.sleep(.03)
        if len(dialogs)!=1:raise AssertionError('Exactly one modal error must be shown per request; observed '+str(len(dialogs)))
        report['oneModalPerRequestObserved']=True
        ui.send(top,process.pid,0x10)
        stdout,stderr=process.communicate(timeout=12)
        if process.returncode or stderr:raise AssertionError('Native UI failed: '+repr(stderr))
        report.update(success=True,completedUtc=datetime.now(timezone.utc).isoformat(),exitCode=process.returncode,stdout=stdout.decode('utf-8'),errorMessages=messages,realNativeUi=True,modalErrorsDismissed=True,inferenceReached=args.scenario=='citation',citationRejectionObserved=args.scenario=='citation',missingModelErrorObserved=args.scenario=='missing-model',limitations=['Fixture inference, not actual model weights.','Keyboard dispatch and owned Win32 controls are exercised; accessibility names/tab order are independently asserted in JVM UI tests.','Filesystem and registry operations require a fresh Process Monitor trace.'])
    finally:
        if process.poll() is None:
            ui.dismiss_owned_dialogs(process.pid)
            for h in ui.windows(process.pid):ui.send(h,process.pid,0x10)
            try:process.communicate(timeout=4)
            except subprocess.TimeoutExpired:process.kill();process.communicate()
        ui.u.SetCursorPos(old_cursor.x,old_cursor.y)
        if old_foreground:ui.u.SetForegroundWindow(old_foreground)
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'success':True,'pid':process.pid,'output':str(output)}))

if __name__=='__main__':main()
