"""Developer-only observation of a newly created EnterpriseAI native process.

Captures only HWNDs belonging to that exact PID, using PrintWindow (never the
desktop). Synthetic drags never click a source. Images/reports live in src/build.
Pillow/numpy are test dependencies, not dependencies of the delivered application.
"""
from __future__ import annotations
import argparse, ctypes as C, ctypes.wintypes as W, hashlib, json, math, os, subprocess, time
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
class INPUTUNION(C.Union):_fields_=[('mouse',MOUSE),('padding',C.c_byte*32)]
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
def blue_components(image):
    rgb=np.asarray(image,dtype=np.int16);mask=(rgb[:,:,2]-rgb[:,:,0]>60)&(rgb[:,:,2]>130)&(rgb[:,:,1]>35)&(rgb[:,:,1]<200)
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
        if classname(hwnd).lower().startswith('swt_window') and width>220 and height>220 and (bounds.left+bounds.right)/2>middle and not any(u.IsWindowVisible(child) for child in children(hwnd,pid)):candidates.append((width*height,hwnd))
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
def yellow_center(image):
    rgb=np.asarray(image,dtype=np.int16);mask=(rgb[:,:,0]>220)&(rgb[:,:,1]>220)&(rgb[:,:,2]<80)
    yy,xx=np.nonzero(mask)
    return (float(np.mean(xx)),float(np.mean(yy))) if len(xx)>20 else None

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--exe',required=True);parser.add_argument('--settle',type=float,default=15);parser.add_argument('--output',default=str(Path(__file__).resolve().parents[2]/'build/test-results/native-graph-visual'));args=parser.parse_args()
    try:u.SetProcessDpiAwarenessContext(C.c_void_p(-4))
    except (AttributeError,OSError):pass
    output=Path(args.output).resolve();source=Path(__file__).resolve().parents[2];build=(source/'build').resolve()
    if not output.is_relative_to(build):raise RuntimeError('Observer outputs must remain inside src/build')
    output.mkdir(parents=True,exist_ok=True);exe=Path(args.exe).resolve();report={'completed':False,'exe':str(exe),'observedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'captures':[],'limitations':['Observes the current Windows laptop, not a clean VM.','Pixel tracking cannot establish exact document IDs or prove internal neighbor force values.','No source click, model invocation, clipboard modification or external viewer is performed.','PrintWindow captures only windows of this newly created process; no desktop fallback.','Filesystem/registry no-write qualification requires Process Monitor separately.']};process=None;held=False;canvas=None;pid=None;old_cursor=POINT();u.GetCursorPos(C.byref(old_cursor));old_foreground=u.GetForegroundWindow()
    try:
        report['exeSha256']=hashlib.file_digest(exe.open('rb'),'sha256').hexdigest()
        graph=subprocess.run([str(exe),'--graph'],cwd=os.environ.get('SystemRoot','C:\\Windows'),capture_output=True,text=True,encoding='utf-8',timeout=20,creationflags=0x08000000)
        if graph.returncode:raise RuntimeError('Graph command failed: '+graph.stdout+graph.stderr)
        graph_data=json.loads(graph.stdout);report['catalogDocuments']=len(graph_data['nodes']);report['confirmedEdges']=len(graph_data['edges']);report['isolatedDocuments']=sum(node['degree']==0 for node in graph_data['nodes'])
        process=subprocess.Popen([str(exe)],cwd=os.environ.get('SystemRoot','C:\\Windows'),stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,creationflags=0x08000000)
        pid=process.pid;deadline=time.monotonic()+10;top=None
        canvas=None
        while time.monotonic()<deadline and process.poll() is None:
            own=windows(pid)
            if own:
                choices=[hwnd for hwnd in own if 'EnterpriseAI' in label(hwnd)] or own
                top=max(choices,key=lambda hwnd:(rect(hwnd).right-rect(hwnd).left)*(rect(hwnd).bottom-rect(hwnd).top))
                try:canvas=graph_canvas(top,pid);break
                except RuntimeError:pass
            time.sleep(.05)
        if not top:raise RuntimeError('Owned main window not found')
        report['windowTree']=[{'class':classname(hwnd),'text':label(hwnd),'bounds':[rect(hwnd).left,rect(hwnd).top,rect(hwnd).right,rect(hwnd).bottom]} for hwnd in children(top,pid)]
        if not canvas:raise RuntimeError('No right-side leaf SWT graph Canvas found after waiting for UI composition')
        u.SetForegroundWindow(top);report['ownedPid']=pid;report['canvasClass']=classname(canvas)
        time.sleep(args.settle)
        if not (exe.parent/'model/inference-worker.exe').is_file():
            edit=[hwnd for hwnd in children(top,pid) if classname(hwnd).lower()=='edit' and not (u.GetWindowLongPtrW(hwnd,-16)&0x800)]
            submit=[hwnd for hwnd in children(top,pid) if classname(hwnd).lower()=='button' and label(hwnd)=='Отправить']
            if edit and submit:
                query=C.create_unicode_buffer('Проверка отсутствующей локальной модели')
                send(edit[-1],pid,0x000C,0,C.addressof(query));send(submit[0],pid,0x00F5)
                time.sleep(.6)
                observed=[label(hwnd) for hwnd in children(top,pid)]
                report['missingModelErrorObserved']=any('не найден' in text.lower() and ('worker' in text.lower() or 'модел' in text.lower()) for text in observed)
                report['missingModelMessages']=[text for text in observed if 'не найден' in text.lower() and ('worker' in text.lower() or 'модел' in text.lower())]
            else:report['missingModelErrorObserved']=False
        def image(name,hwnd=canvas):
            path=output/(name+'.png');pic=capture(hwnd,pid,path);report['captures'].append(str(path));return pic
        full=image('01-window-before',top);before=image('02-graph-before');baseline=blue_components(before)
        report['blueVisibleNodeComponents']=len(baseline)
        if not baseline:raise RuntimeError('No blue node fills in owned Canvas capture')
        width,height=before.size;options=[p for p in baseline if width*.15<p['x']<width*.85 and height*.15<p['y']<height*.85]
        picked=max(options or baseline,key=lambda p:p['area']);px,py=round(picked['x']),round(picked['y']);tx=min(width-40,px+min(170,round(width*.25)));ty=max(35,py-65)
        report['dragStartPixel']=[px,py];report['dragTargetPixel']=[tx,ty]
        static_before=[label(hwnd) for hwnd in children(top,pid) if classname(hwnd).lower()=='static'];own_before=set(windows(pid))
        u.SetForegroundWindow(top);move_owned(canvas,top,pid,px,py);time.sleep(.1);mouse_input(0x0002);held=True;time.sleep(.1)
        for tick in range(1,13):
            xx=px+(tx-px)*tick/12;yy=py+(ty-py)*tick/12;move_owned(canvas,top,pid,xx,yy);time.sleep(.07)
        time.sleep(.45);during=image('03-graph-during-drag');during_components=blue_components(during);image('04-window-during-drag',top)
        yellow=yellow_center(during);report['heldYellowCenter']=yellow
        if yellow is None or math.hypot(yellow[0]-tx,yellow[1]-ty)>30 or math.hypot(yellow[0]-px,yellow[1]-py)<60:
            send(canvas,pid,0x0100,0x1B);raise RuntimeError('Real drag failed visible pinned-target gate; Escape aborts before mouseup/source click')
        mouse_input(0x0004);held=False;time.sleep(2.5);after=image('05-graph-after-release');image('06-window-after-release',top)
        report['realDragTargetVerified']=True
        report['visibleBlueNodeMotionDuringDrag']=nearest_motion(baseline,during_components)
        report['visibleBlueNodeMotionAfterRelease']=nearest_motion(during_components,blue_components(after))
        static_after=[label(hwnd) for hwnd in children(top,pid) if classname(hwnd).lower()=='static'];report['newOwnedTopLevelWindowsAfterDrag']=len(set(windows(pid))-own_before);report['sourceOpeningMessageObserved']=any('Проверка версии оригинала' in text or 'передан в диалог Windows' in text for text in static_after)
        report['graphInformation']=[text for text in static_after if 'Документов:' in text]
        # Pan from a corner outside the rounded node cloud, avoiding a source click.
        move_owned(canvas,top,pid,12,12);mouse_input(0x0002);held=True;time.sleep(.08)
        for tick in range(1,9):move_owned(canvas,top,pid,12+70*tick/8,12+35*tick/8);time.sleep(.03)
        mouse_input(0x0004);held=False;time.sleep(.3);pan=image('07-graph-after-pan')
        move_owned(canvas,top,pid,width//2,height//2);mouse_input(0x0800,120);time.sleep(.3);zoom=image('08-graph-after-zoom');image('09-window-after-pan-zoom',top)
        report['panChangedPixels']=int(np.count_nonzero(np.any(np.asarray(after)!=np.asarray(pan),axis=2)));report['zoomChangedPixels']=int(np.count_nonzero(np.any(np.asarray(pan)!=np.asarray(zoom),axis=2)))
        report['noSourceOpenObserved']=report['newOwnedTopLevelWindowsAfterDrag']==0 and not report['sourceOpeningMessageObserved']
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
        u.SetCursorPos(old_cursor.x,old_cursor.y)
        if old_foreground:u.SetForegroundWindow(old_foreground)
        (output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'completed':report['completed'],'output':str(output),'documents':report.get('catalogDocuments'),'edges':report.get('confirmedEdges'),'noSourceOpenObserved':report.get('noSourceOpenObserved')},ensure_ascii=False))

if __name__=='__main__':main()
