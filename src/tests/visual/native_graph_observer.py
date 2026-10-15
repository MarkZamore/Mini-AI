"""Developer-only observation of a newly created EnterpriseAI native process.

Captures only HWNDs belonging to that exact PID, using PrintWindow (never the
desktop). Synthetic drags never click a source. Images/reports live in src/build.
Pillow/numpy are test dependencies, not dependencies of the delivered application.
"""
from __future__ import annotations
import argparse, ctypes as C, ctypes.wintypes as W, hashlib, json, math, os, subprocess, time
from datetime import datetime, timezone
from pathlib import Path
from collections import deque
import numpy as np
from PIL import Image

u=C.WinDLL('user32',use_last_error=True);g=C.WinDLL('gdi32',use_last_error=True)
CALLBACK=C.WINFUNCTYPE(W.BOOL,W.HWND,W.LPARAM)
class RECT(C.Structure):_fields_=[('left',W.LONG),('top',W.LONG),('right',W.LONG),('bottom',W.LONG)]
class POINT(C.Structure):_fields_=[('x',W.LONG),('y',W.LONG)]
class HEADER(C.Structure):_fields_=[('size',W.DWORD),('width',W.LONG),('height',W.LONG),('planes',W.WORD),('bits',W.WORD),('compression',W.DWORD),('image_size',W.DWORD),('xppm',W.LONG),('yppm',W.LONG),('used',W.DWORD),('important',W.DWORD)]
class INFO(C.Structure):_fields_=[('header',HEADER),('colors',W.DWORD*3)]
class MOUSE(C.Structure):_fields_=[('dx',W.LONG),('dy',W.LONG),('data',W.DWORD),('flags',W.DWORD),('time',W.DWORD),('extra',C.c_size_t)]
class KEYBOARD(C.Structure):_fields_=[('vk',W.WORD),('scan',W.WORD),('flags',W.DWORD),('time',W.DWORD),('extra',C.c_size_t)]
class INPUTUNION(C.Union):_fields_=[('mouse',MOUSE),('keyboard',KEYBOARD),('padding',C.c_byte*32)]
class INPUT(C.Structure):_fields_=[('kind',W.DWORD),('value',INPUTUNION)]
u.EnumWindows.argtypes=[CALLBACK,W.LPARAM];u.EnumChildWindows.argtypes=[W.HWND,CALLBACK,W.LPARAM]
u.GetWindowThreadProcessId.argtypes=[W.HWND,C.POINTER(W.DWORD)];u.GetWindowThreadProcessId.restype=W.DWORD
u.IsWindowVisible.argtypes=[W.HWND];u.IsWindowVisible.restype=W.BOOL
u.GetWindowRect.argtypes=[W.HWND,C.POINTER(RECT)];u.GetClientRect.argtypes=[W.HWND,C.POINTER(RECT)]
u.GetClassNameW.argtypes=[W.HWND,W.LPWSTR,C.c_int];u.GetWindowTextW.argtypes=[W.HWND,W.LPWSTR,C.c_int]
u.GetWindowDC.argtypes=[W.HWND];u.GetWindowDC.restype=W.HDC
u.ReleaseDC.argtypes=[W.HWND,W.HDC];u.PrintWindow.argtypes=[W.HWND,W.HDC,W.UINT];u.PrintWindow.restype=W.BOOL
u.SendMessageTimeoutW.argtypes=[W.HWND,W.UINT,W.WPARAM,W.LPARAM,W.UINT,W.UINT,C.POINTER(C.c_size_t)];u.SendMessageTimeoutW.restype=W.LPARAM
u.SetForegroundWindow.argtypes=[W.HWND];u.SetForegroundWindow.restype=W.BOOL
u.GetWindowLongPtrW.argtypes=[W.HWND,C.c_int];u.GetWindowLongPtrW.restype=C.c_ssize_t
u.ClientToScreen.argtypes=[W.HWND,C.POINTER(POINT)]
u.GetCursorPos.argtypes=[C.POINTER(POINT)];u.SetCursorPos.argtypes=[C.c_int,C.c_int];u.SetCursorPos.restype=W.BOOL
u.GetForegroundWindow.restype=W.HWND
u.GetWindow.argtypes=[W.HWND,W.UINT];u.GetWindow.restype=W.HWND
u.SendInput.argtypes=[W.UINT,C.POINTER(INPUT),C.c_int];u.SendInput.restype=W.UINT
g.CreateCompatibleDC.argtypes=[W.HDC];g.CreateCompatibleDC.restype=W.HDC
g.CreateDIBSection.argtypes=[W.HDC,C.POINTER(INFO),W.UINT,C.POINTER(C.c_void_p),W.HANDLE,W.DWORD];g.CreateDIBSection.restype=W.HBITMAP
g.SelectObject.argtypes=[W.HDC,W.HANDLE];g.SelectObject.restype=W.HANDLE
g.DeleteObject.argtypes=[W.HANDLE];g.DeleteDC.argtypes=[W.HDC]

def owner(hwnd):
    pid=W.DWORD();u.GetWindowThreadProcessId(hwnd,C.byref(pid));return pid.value
def rect(hwnd):
    value=RECT();
    if not u.GetWindowRect(hwnd,C.byref(value)):raise OSError(C.get_last_error(),'GetWindowRect')
    return value
def label(hwnd):
    text=C.create_unicode_buffer(8192);u.GetWindowTextW(hwnd,text,len(text));return text.value
def classname(hwnd):
    text=C.create_unicode_buffer(128);u.GetClassNameW(hwnd,text,len(text));return text.value
def windows(pid):
    found=[]
    @CALLBACK
    def collect(hwnd,param):
        if owner(hwnd)==pid and u.IsWindowVisible(hwnd):found.append(hwnd)
        return True
    u.EnumWindows(collect,0);return found
def children(hwnd,pid):
    found=[]
    @CALLBACK
    def collect(child,param):
        if owner(child)==pid:found.append(child)
        return True
    u.EnumChildWindows(hwnd,collect,0);return found
def send(hwnd,pid,message,wparam=0,lparam=0):
    if owner(hwnd)!=pid:raise RuntimeError('Refusing to act on a foreign window')
    result=C.c_size_t()
    if not u.SendMessageTimeoutW(hwnd,message,wparam,lparam,0x2,3000,C.byref(result)):raise OSError(C.get_last_error(),'Owned window message timed out')
    return result.value
def control_text(hwnd,pid):
    # GetWindowText deliberately cannot read another process's Edit contents.
    length=send(hwnd,pid,0x000E)
    if length>2_000_000:raise RuntimeError('Owned control text exceeds observer bound')
    buffer=C.create_unicode_buffer(length+1)
    send(hwnd,pid,0x000D,len(buffer),C.addressof(buffer))
    return buffer.value
def xy(x,y):return (int(x)&0xffff)|((int(y)&0xffff)<<16)
def capture(hwnd,pid,path):
    if owner(hwnd)!=pid:raise RuntimeError('Refusing to capture a foreign window')
    bounds=rect(hwnd);width=bounds.right-bounds.left;height=bounds.bottom-bounds.top
    dc=u.GetWindowDC(hwnd);memory=g.CreateCompatibleDC(dc);bits=C.c_void_p()
    info=INFO(HEADER(C.sizeof(HEADER),width,-height,1,32,0,width*height*4,0,0,0,0))
    bitmap=g.CreateDIBSection(dc,C.byref(info),0,C.byref(bits),None,0);old=g.SelectObject(memory,bitmap)
    try:
        if not u.PrintWindow(hwnd,memory,2):raise OSError(C.get_last_error(),'PrintWindow failed; no desktop fallback is allowed')
        data=C.string_at(bits.value,width*height*4);image=Image.frombytes('RGB',(width,height),data,'raw','BGRX')
        image.save(path);return image
    finally:g.SelectObject(memory,old);g.DeleteObject(bitmap);g.DeleteDC(memory);u.ReleaseDC(hwnd,dc)
def highlight(rgb,level):
    return tuple(math.floor(c+(255-c)*level/100.0+.5) for c in rgb)
def gradient_palette():
    return [tuple(math.floor(a+(b-a)*i/63+.5) for a,b in zip((210,202,222),(159,129,195))) for i in range(64)]
def palette_mask(rgb,palette,tolerance=1):
    data=rgb.astype(np.uint32);encoded=(data[:,:,0]<<16)|(data[:,:,1]<<8)|data[:,:,2]
    values=set()
    for r,g,b in palette:
        for dr in range(-tolerance,tolerance+1):
            for dg in range(-tolerance,tolerance+1):
                for db in range(-tolerance,tolerance+1):values.add(((int(r)+dr)<<16)|((int(g)+dg)<<8)|(int(b)+db))
    return np.isin(encoded,np.array(sorted(values),dtype=np.uint32))
def pulse_palette():
    return [highlight(rgb,level) for rgb in gradient_palette()+[(226,221,233)] for level in range(7)]
def node_components(image,palette="pink"):
    rgb=np.asarray(image,dtype=np.int16)
    fills=[(121,102,143)] if palette=='selection-ring' else pulse_palette()
    mask=palette_mask(rgb,fills) if palette in ('pink','selection-ring') else ((rgb[:,:,2]-rgb[:,:,0]>60)&(rgb[:,:,2]>130)&(rgb[:,:,1]>35)&(rgb[:,:,1]<200))
    seen=np.zeros(mask.shape,dtype=bool);height,width=mask.shape;components=[]
    for sy,sx in zip(*np.nonzero(mask)):
        if seen[sy,sx]:continue
        queue=deque([(int(sx),int(sy))]);seen[sy,sx]=True;points=[]
        while queue:
            x,y=queue.popleft();points.append((x,y))
            for nx,ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
                if 0<=nx<width and 0<=ny<height and mask[ny,nx] and not seen[ny,nx]:seen[ny,nx]=True;queue.append((nx,ny))
        if len(points)>=4:
            xs,ys=zip(*points);components.append({'x':sum(xs)/len(xs),'y':sum(ys)/len(ys),'area':len(points),'width':max(xs)-min(xs)+1,'height':max(ys)-min(ys)+1})
    return components
def graph_canvas(top,pid):
    top_rect=rect(top);middle=(top_rect.left+top_rect.right)/2;candidates=[]
    for hwnd in children(top,pid):
        bounds=rect(hwnd);width=bounds.right-bounds.left;height=bounds.bottom-bounds.top
        if classname(hwnd).lower().startswith('swt_window') and width>220 and height>220 and (bounds.left+bounds.right)/2>middle and not any(u.IsWindowVisible(child) and (rect(child).right-rect(child).left)*(rect(child).bottom-rect(child).top)>width*height*.1 for child in children(hwnd,pid)):candidates.append((width*height,hwnd))
    if not candidates:raise RuntimeError('No right-side leaf SWT graph Canvas found')
    return max(candidates)[1]
def nearest_motion(before,after):
    if not before or not after:return None
    shifts=[]
    for item in before:
        match=min(after,key=lambda p:(p['x']-item['x'])**2+(p['y']-item['y'])**2)
        shifts.append(math.hypot(match['x']-item['x'],match['y']-item['y']))
    return {'medianNearestPixelMotion':float(np.median(shifts)),'p95NearestPixelMotion':float(np.percentile(shifts,95)),'count':len(shifts)}
def mouse_input(flags,data=0):
    value=INPUT(0,INPUTUNION(mouse=MOUSE(0,0,data,flags,0,0)))
    if u.SendInput(1,C.byref(value),C.sizeof(INPUT))!=1:raise OSError(C.get_last_error(),'Real owned-window input was not accepted')
def move_owned(canvas,top,pid,x,y):
    if owner(u.GetForegroundWindow())!=pid:raise RuntimeError('Input aborted: another app/UAC has foreground focus')
    point=POINT(round(x),round(y));u.ClientToScreen(canvas,C.byref(point))
    if not u.SetCursorPos(point.x,point.y):raise OSError(C.get_last_error(),'Owned cursor position failed')
def key_input(top,pid,vk,shift=False):
    if owner(u.GetForegroundWindow())!=pid:raise RuntimeError('Input aborted: owned process lacks focus')
    def event(code,up=False):
        value=INPUT(1,INPUTUNION(keyboard=KEYBOARD(code,0,2 if up else 0,0,0)))
        if u.SendInput(1,C.byref(value),C.sizeof(INPUT))!=1:raise OSError(C.get_last_error(),'Owned keyboard input failed')
    if shift:event(0x10)
    event(vk);event(vk,True)
    if shift:event(0x10,True)
def dismiss_owned_dialogs(pid,dialog_records=None):
    messages=[]
    for hwnd in windows(pid):
        parent=u.GetWindow(hwnd,4)
        native_dialog=classname(hwnd)=='#32770'
        themed_dialog=classname(hwnd).lower().startswith('swt_window') and parent and owner(parent)==pid
        if native_dialog or themed_dialog:
            labels=[label(c) for c in children(hwnd,pid) if classname(c).lower()=='static' and label(c)]
            if not labels:continue
            if dialog_records is not None:dialog_records.append({'hwnd':int(hwnd),'owner':int(parent or 0),'caption':label(hwnd),'messages':labels})
            messages.extend(labels)
            if themed_dialog:send(hwnd,pid,0x0010)
            else:
                buttons=[c for c in children(hwnd,pid) if classname(c).lower()=='button']
                # SWT DIALOG_TRIM can use native #32770 while its OK action
                # is a custom Canvas; the class alone does not imply Button.
                if buttons:send(buttons[0],pid,0x00F5)
                elif parent and owner(parent)==pid:send(hwnd,pid,0x0010)
                else:raise RuntimeError('Cannot identify owned modal dismissal')
    return messages
def process_cpu_seconds(process):
    kernel=C.WinDLL('kernel32',use_last_error=True)
    kernel.GetProcessTimes.argtypes=[W.HANDLE,*([C.POINTER(W.FILETIME)]*4)];kernel.GetProcessTimes.restype=W.BOOL
    created,exited,kernel_time,user_time=(W.FILETIME() for _ in range(4))
    if not kernel.GetProcessTimes(process._handle,C.byref(created),C.byref(exited),C.byref(kernel_time),C.byref(user_time)):raise OSError(C.get_last_error(),'Owned process CPU times')
    return sum((v.dwHighDateTime<<32)|v.dwLowDateTime for v in (kernel_time,user_time))/10000000

def yellow_center(image):
    rgb=np.asarray(image,dtype=np.int16);mask=(rgb[:,:,0]>220)&(rgb[:,:,1]>220)&(rgb[:,:,2]<80)
    yy,xx=np.nonzero(mask)
    return (float(np.mean(xx)),float(np.mean(yy))) if len(xx)>20 else None

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--exe',required=True);parser.add_argument('--settle',type=float,default=15);parser.add_argument('--palette',choices=['pink','legacy'],default='pink');parser.add_argument('--idle-seconds',type=float,default=30);parser.add_argument('--catalog-json',type=Path);parser.add_argument('--output',default=str(Path(__file__).resolve().parents[2]/'build/test-results/dusty-yellow-native-graph-visual'));args=parser.parse_args()
    try:u.SetProcessDpiAwarenessContext(C.c_void_p(-4))
    except (AttributeError,OSError):pass
    output=Path(args.output).resolve();source=Path(__file__).resolve().parents[2];build=(source/'build').resolve()
    if not output.is_relative_to(build):raise RuntimeError('Observer outputs must remain inside src/build')
    output.mkdir(parents=True,exist_ok=True);exe=Path(args.exe).resolve();report={'completed':False,'exe':str(exe),'observedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'captures':[],'limitations':['Observes the current Windows laptop, not a clean VM.','Pixel tracking cannot establish exact document IDs or prove internal neighbor force values.','No external viewer is launched; double-click sources is tested only against a physical fixture without docs.','PrintWindow captures only windows of this newly created process; no desktop fallback.','This graph observer does not verify transient filesystem operations or registry changes.']};process=None;held=False;canvas=None;pid=None;old_cursor=POINT();u.GetCursorPos(C.byref(old_cursor));old_foreground=u.GetForegroundWindow()
    try:
        report['exeSha256']=hashlib.file_digest(exe.open('rb'),'sha256').hexdigest()
        if args.catalog_json:
            catalog=args.catalog_json.resolve()
            if not catalog.is_relative_to(build):raise RuntimeError('Prepared graph catalog must be within src/build')
            graph_data=json.loads(catalog.read_text(encoding='utf-8-sig'));report['preparedCatalogSha256']=hashlib.file_digest(catalog.open('rb'),'sha256').hexdigest()
        else:
            graph=subprocess.run([str(exe),'--graph'],cwd=os.environ.get('SystemRoot','C:\\Windows'),capture_output=True,text=True,encoding='utf-8',timeout=20,creationflags=0x08000000)
            if graph.returncode:raise RuntimeError('Graph command failed: '+graph.stdout+graph.stderr)
            graph_data=json.loads(graph.stdout)
        report['catalogDocuments']=len(graph_data['nodes']);report['confirmedEdges']=len(graph_data['edges']);report['isolatedDocuments']=sum(node['degree']==0 for node in graph_data['nodes'])
        report['applicationStartedUtc']=datetime.now(timezone.utc).isoformat();report['ordinaryParentPid']=os.getpid();report['arguments']=[]
        process=subprocess.Popen([str(exe)],cwd=os.environ.get('SystemRoot','C:\\Windows'),stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,creationflags=0x08000000)
        pid=process.pid;report['ownedPid']=pid;deadline=time.monotonic()+10;top=None
        canvas=None
        while time.monotonic()<deadline and process.poll() is None:
            own=windows(pid)
            if own:
                choices=[hwnd for hwnd in own if label(hwnd).startswith('Mini-AI')] or own
                top=max(choices,key=lambda hwnd:(rect(hwnd).right-rect(hwnd).left)*(rect(hwnd).bottom-rect(hwnd).top))
                try:canvas=graph_canvas(top,pid);break
                except RuntimeError:pass
            time.sleep(.05)
        if not top:raise RuntimeError('Owned main window not found')
        u.GetDpiForWindow.argtypes=[W.HWND];u.GetDpiForWindow.restype=W.UINT
        report['actualNativeWindowDpi']=u.GetDpiForWindow(top)
        report['windowTree']=[{'class':classname(hwnd),'text':label(hwnd),'bounds':[rect(hwnd).left,rect(hwnd).top,rect(hwnd).right,rect(hwnd).bottom]} for hwnd in children(top,pid)]
        if not canvas:raise RuntimeError('No right-side leaf SWT graph Canvas found after waiting for UI composition')
        report['startupDialogsDismissed']=dismiss_owned_dialogs(pid)
        u.SetForegroundWindow(top);report['ownedPid']=pid;report['canvasClass']=classname(canvas)
        time.sleep(args.settle)
        report['startupDialogsDismissed'].extend(dismiss_owned_dialogs(pid));u.SetForegroundWindow(top)
        def image(name,hwnd=canvas):
            path=output/(name+'.png');pic=capture(hwnd,pid,path);report['captures'].append(str(path));return pic
        full=image('01-window-before',top);before=image('02-graph-before');baseline=node_components(before,args.palette)
        report['visibleNodePalette']='purple-degree-gradient' if args.palette=='pink' else 'legacy-blue';report['visibleNodeComponents']=len(baseline)
        if not baseline:raise RuntimeError('No expected node fills in owned Canvas capture')
        width,height=before.size;options=[p for p in baseline if width*.15<p['x']<width*.85 and height*.15<p['y']<height*.85]
        picked=max(options or baseline,key=lambda p:p['area']);px,py=round(picked['x']),round(picked['y']);tx=min(width-40,px+min(170,round(width*.25)));ty=max(35,py-65)
        report['dragStartPixel']=[px,py];report['dragTargetPixel']=[tx,ty]
        own_before=set(windows(pid))
        # A single click only selects and adds the full-title footer.
        move_owned(canvas,top,pid,px,py);mouse_input(0x0002);mouse_input(0x0004);time.sleep(.25)
        footer_titles=[label(hwnd) for hwnd in children(top,pid) if classname(hwnd).lower()=='static' and u.IsWindowVisible(hwnd) and label(hwnd)]
        document_titles={node['document']['title'] for node in graph_data['nodes']}
        selected_titles=[text for text in footer_titles if text in document_titles]
        if len(selected_titles)!=1:raise RuntimeError('Single click must expose exactly one full document title in footer')
        if set(windows(pid))!=own_before:raise RuntimeError('Single click unexpectedly opened a source/error window')
        report['singleClickFooterTitle']=selected_titles[0];report['singleClickDoesNotOpen']=True
        before=image('02b-graph-after-selection');baseline=node_components(before,args.palette);width,height=before.size
        picked=max(baseline,key=lambda p:p['area']);px,py=round(picked['x']),round(picked['y']);tx=min(width-40,px+min(170,round(width*.25)));ty=max(35,py-65)
        report['dragStartPixel']=[px,py];report['dragTargetPixel']=[tx,ty]
        u.SetForegroundWindow(top);move_owned(canvas,top,pid,px,py);time.sleep(.1);mouse_input(0x0002);held=True;time.sleep(.1)
        for tick in range(1,13):
            xx=px+(tx-px)*tick/12;yy=py+(ty-py)*tick/12;move_owned(canvas,top,pid,xx,yy);time.sleep(.07)
        time.sleep(.45);during=image('03-graph-during-drag');during_components=node_components(during,args.palette);image('04-window-during-drag',top)
        if args.palette=='pink':
            during_rgb=np.asarray(during,dtype=np.int16)
            pulsed_only=palette_mask(during_rgb,pulse_palette(),0)&~palette_mask(during_rgb,gradient_palette()+[(226,221,233)],0)
            report['heldPulseOnlyFillPixels']=int(np.count_nonzero(pulsed_only))
            if report['heldPulseOnlyFillPixels']:raise RuntimeError('Heartbeat colour was not paused during actual node drag')
            report['pulsePausedDuringDragObserved']=True
        selected_components=node_components(during,'selection-ring') if args.palette=='pink' else []
        yellow=yellow_center(during) if args.palette=='legacy' else ((lambda q:(q['x'],q['y']))(min(selected_components,key=lambda q:math.hypot(q['x']-tx,q['y']-ty))) if selected_components else None);report['heldNodeCenter']=yellow;report['heldSelectionRingVerified']=args.palette=='pink' and yellow is not None
        if yellow is None or math.hypot(yellow[0]-tx,yellow[1]-ty)>30 or math.hypot(yellow[0]-px,yellow[1]-py)<60:
            send(canvas,pid,0x0100,0x1B);raise RuntimeError('Real drag failed visible pinned-target gate; Escape aborts before mouseup/source click')
        mouse_input(0x0004);held=False;time.sleep(2.5);after=image('05-graph-after-release');image('06-window-after-release',top)
        report['realDragTargetVerified']=True
        report['visibleNodeMotionDuringDrag']=nearest_motion(baseline,during_components)
        report['visibleNodeMotionAfterRelease']=nearest_motion(during_components,node_components(after,args.palette))
        static_after=[label(hwnd) for hwnd in children(top,pid) if classname(hwnd).lower()=='static'];report['newOwnedTopLevelWindowsAfterDrag']=len(set(windows(pid))-own_before);report['sourceOpeningMessageObserved']=any('Проверка версии оригинала' in text or 'передан в диалог Windows' in text for text in static_after)
        report['graphInformation']=[text for text in static_after if 'Документов:' in text]
        # Pan from a corner outside the rounded node cloud, avoiding a source click.
        move_owned(canvas,top,pid,12,12);mouse_input(0x0002);held=True;time.sleep(.08)
        for tick in range(1,9):move_owned(canvas,top,pid,12+70*tick/8,12+35*tick/8);time.sleep(.03)
        mouse_input(0x0004);held=False;time.sleep(.3);pan=image('07-graph-after-pan')
        move_owned(canvas,top,pid,width//2,height//2);mouse_input(0x0800,120);time.sleep(.3);zoom=image('08-graph-after-zoom');image('09-window-after-pan-zoom',top)
        report['panChangedPixels']=int(np.count_nonzero(np.any(np.asarray(after)!=np.asarray(pan),axis=2)));report['zoomChangedPixels']=int(np.count_nonzero(np.any(np.asarray(pan)!=np.asarray(zoom),axis=2)))
        report['noSourceOpenObserved']=report['newOwnedTopLevelWindowsAfterDrag']==0 and not report['sourceOpeningMessageObserved']
        if not (exe.parent/'docs').exists():
            components=node_components(zoom,args.palette)
            candidate=max(components,key=lambda q:q['area']);dx,dy=round(candidate['x']),round(candidate['y'])
            move_owned(canvas,top,pid,dx,dy)
            mouse_input(2);mouse_input(4);time.sleep(.08);mouse_input(2);mouse_input(4)
            deadline=time.monotonic()+6;source_errors=[]
            while time.monotonic()<deadline:
                source_errors.extend(dismiss_owned_dialogs(pid))
                if source_errors:break
                time.sleep(.04)
            if not source_errors:raise RuntimeError('Double click did not invoke missing-original source error')
            report['doubleClickSourceErrorObserved']=True;report['doubleClickSourceMessages']=source_errors
            report['doubleClickExternalViewerLaunched']=False
        else:
            report['doubleClickSourceErrorObserved']=None
            report['doubleClickQualification']='Skipped here to prevent opening real originals; acceptance must supply a physical index fixture without docs.'
        u.SetForegroundWindow(top)
        top_box=rect(top);top_width=top_box.right-top_box.left
        sashes=[h for h in children(top,pid) if classname(h).lower().startswith('swt_') and 6<=rect(h).right-rect(h).left<=50 and rect(h).bottom-rect(h).top>220 and top_box.left+top_width*.3<rect(h).left<top_box.left+top_width*.7]
        if len(sashes)!=1:raise RuntimeError('Cannot unambiguously identify the owned split divider')
        sash=sashes[0];old_canvas_width=rect(canvas).right-rect(canvas).left;box=rect(sash)
        grip=image('09a-divider-visible-before-drag',sash);grip_rgb=np.asarray(grip,dtype=np.int16)
        grip_width,grip_height=grip.size;half_region=max(24,round(24*report['actualNativeWindowDpi']/96))
        centre_start=max(0,grip_height//2-half_region);centre_end=min(grip_height,grip_height//2+half_region)
        centre=grip_rgb[centre_start:centre_end]
        accent_mask=palette_mask(grip_rgb,[(168,153,111)])
        centre_accent=int(np.count_nonzero(accent_mask[centre_start:centre_end]));outside_accent=int(np.count_nonzero(accent_mask))-centre_accent
        centre_background=int(np.count_nonzero(palette_mask(centre,[(221,212,187),(201,188,149)])))
        if centre_accent<12 or outside_accent or centre_background<centre.shape[0]*centre.shape[1]*.5:
            raise RuntimeError('Visible divider grip gate failed: expected small accent dots at centre against separator background')
        report['dividerGripVisible']=True;report['dividerGripPixels']={'accentRgb':[168,153,111],'centreRegion':[0,centre_start,grip_width,centre_end],'centreAccentPixels':centre_accent,'outsideCentreAccentPixels':outside_accent,'centreBackgroundPixels':centre_background,'actualWindowDpi':report['actualNativeWindowDpi']}
        point=POINT((box.right-box.left)//2,(box.bottom-box.top)//2);u.ClientToScreen(sash,C.byref(point))
        move_owned(sash,top,pid,(box.right-box.left)//2,(box.bottom-box.top)//2);mouse_input(2);held=True
        for tick in range(1,9):
            if owner(u.GetForegroundWindow())!=pid:raise RuntimeError('Divider gesture lost owned foreground')
            u.SetCursorPos(point.x+round(40*tick/8),point.y);time.sleep(.04)
        mouse_input(4);held=False;time.sleep(.2)
        new_canvas_width=rect(canvas).right-rect(canvas).left
        if abs(new_canvas_width-old_canvas_width)<15:raise RuntimeError('Real divider drag did not resize the graph pane')
        report['dividerDragVerified']=True;report['graphWidthsBeforeAfterDivider']=[old_canvas_width,new_canvas_width];image('09b-window-after-divider',top)
        # Render-only heartbeat is allowed only through exact documented colours.
        time.sleep(args.settle);u.SetForegroundWindow(top)
        base_colours=gradient_palette()+[(226,221,233),(184,176,198),(153,136,175)]
        idle_before=image('10-graph-idle-before');idle_rgb=np.asarray(idle_before,dtype=np.int16)
        components=node_components(idle_before,args.palette)
        anchors=[(round(q['x']),round(q['y'])) for q in sorted(components,key=lambda q:q['area'],reverse=True)[:16]]
        deadline=time.monotonic()+1.5
        def baseline_ready():
            return all(tuple(idle_rgb[y,x]) in base_colours for x,y in anchors) and any(np.any(np.all(idle_rgb==edge,axis=2)) for edge in [(184,176,198),(153,136,175)])
        while not baseline_ready() and time.monotonic()<deadline:
            time.sleep(.07);idle_before=capture(canvas,pid,output/'10-graph-idle-before.png');idle_rgb=np.asarray(idle_before,dtype=np.int16)
        if not anchors or not baseline_ready():raise RuntimeError('Cannot establish exact baseline node/edge fills before pulse observation')
        anchor_base=[tuple(idle_rgb[y,x]) for x,y in anchors]
        masks=[np.all(idle_rgb==colour,axis=2) for colour in base_colours]
        expected=[idle_rgb.copy() for _ in range(7)]
        for level in range(1,7):
            for colour,mask in zip(base_colours,masks):expected[level][mask]=highlight(colour,level)
        animation=W.BOOL();u.SystemParametersInfoW(0x1042,0,C.byref(animation),0)
        idle_cpu_before=process_cpu_seconds(process);idle_started=time.monotonic();samples=[];peaks=[];last_active=-10;peak=None;max_unknown=0
        while time.monotonic()-idle_started<max(30,args.idle_seconds):
            if owner(u.GetForegroundWindow())!=pid:raise RuntimeError('Pulse observation aborted: owned app lost foreground focus')
            sample_time=time.monotonic()-idle_started
            shot=capture(canvas,pid,output/'11-graph-idle-last.png');pixels=np.asarray(shot,dtype=np.int16)
            possible=set(range(7))
            for (x,y),base_colour in zip(anchors,anchor_base):possible&={level for level in range(7) if tuple(pixels[y,x])==highlight(base_colour,level)}
            if not possible:raise RuntimeError('A node centre moved or acquired an undocumented colour during idle')
            differences={level:int(np.count_nonzero(np.any(pixels!=expected[level],axis=2))) for level in possible}
            level=min(differences,key=differences.get);unknown=differences[level];max_unknown=max(max_unknown,unknown)
            if unknown:raise RuntimeError('Undocumented geometry/colour changes during idle pulse: '+str(unknown)+' pixels')
            samples.append({'seconds':round(sample_time,3),'level':level,'unexplainedPixels':unknown})
            if level>0:
                if sample_time-last_active>1:
                    peak={'seconds':sample_time,'maximumLevel':level};peaks.append(peak)
                elif level>peak['maximumLevel']:peak['maximumLevel']=level
                last_active=sample_time
            time.sleep(.08)
        idle_cpu_after=process_cpu_seconds(process)
        cadence=[peaks[i]['seconds']-peaks[i-1]['seconds'] for i in range(1,len(peaks))]
        if animation.value and (len(peaks)<2 or any(not 6.8<=gap<=9.2 for gap in cadence)):raise RuntimeError('Expected rare pulse cadence not observed: '+str(peaks))
        report['idleProcessCpuSeconds']=idle_cpu_after-idle_cpu_before;report['idleObservedSeconds']=time.monotonic()-idle_started
        if report['idleProcessCpuSeconds']>4:raise RuntimeError('Observed idle rendering CPU exceeds4seconds over30seconds (including observer-induced PrintWindow paints)')
        report['idleRenderingCpuLow']=True
        report['pulseSamples']=samples;report['pulsePeaks']=peaks;report['pulseCadenceSeconds']=cadence;report['windowsClientAreaAnimationEnabled']=bool(animation.value)
        report['idleUnexplainedPixels']=max_unknown;report['nativeIdleGeometryStable']=True;report['internalFrameWakeCountVerified']=False
        report['idleQualification']='All native pixels match the baseline transformed only through globally shared documented 0..6% node/edge highlight; positions/strokes/background unchanged. PrintWindow itself requests paint. Exact world/no-physics-frame and 5DIP-gap are separately tested.'
        report['completed']=True
    except Exception as error:report['error']=type(error).__name__+': '+str(error);raise
    finally:
        if held:
            if canvas is not None and pid is not None:
                try:send(canvas,pid,0x0100,0x1B)
                except Exception:pass
            try:mouse_input(0x0004)
            except Exception:pass
        if process is not None:
            own=windows(process.pid) if process.poll() is None else []
            for hwnd in own:
                try:send(hwnd,process.pid,0x0010)
                except Exception:pass
            try:process.wait(timeout=6)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=3)
            report['childExitCode']=process.returncode
            report['applicationFinishedUtc']=datetime.now(timezone.utc).isoformat();report['stdout']='';report['stderr']=process.stderr.read().decode('utf-8')
        u.SetCursorPos(old_cursor.x,old_cursor.y)
        if old_foreground:u.SetForegroundWindow(old_foreground)
        (output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'completed':report['completed'],'output':str(output),'documents':report.get('catalogDocuments'),'edges':report.get('confirmedEdges'),'noSourceOpenObserved':report.get('noSourceOpenObserved')},ensure_ascii=False))

if __name__=='__main__':main()
