"""Observe the owned native Mini-AI window and icon; observer artifacts only."""
import argparse, ctypes as C, hashlib, importlib.util, json, os, subprocess, time
from datetime import datetime, timezone
from pathlib import Path
import numpy as np

spec=importlib.util.spec_from_file_location('owned_graph_observer',Path(__file__).parents[1]/'visual/native_graph_observer.py')
ui=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui)

def main():
    try:ui.u.SetProcessDpiAwarenessContext(C.c_void_p(-4))
    except (AttributeError,OSError):pass
    parser=argparse.ArgumentParser();parser.add_argument('--exe',required=True);parser.add_argument('--output');args=parser.parse_args()
    exe=Path(args.exe).resolve();build=Path(__file__).resolve().parents[2]/'build'
    output=Path(args.output).resolve() if args.output else build/'test-results/dusty-yellow-branding-ui.json'
    if not output.is_relative_to(build.resolve()):raise ValueError('Observer artifacts must be under src/build')
    output.parent.mkdir(parents=True,exist_ok=True)
    started=datetime.now(timezone.utc).isoformat()
    child=subprocess.Popen([str(exe),'--ui-smoke'],cwd=os.environ['SystemRoot'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:
        deadline=time.monotonic()+4;top=None
        while time.monotonic()<deadline and child.poll() is None:
            top=next((h for h in ui.windows(child.pid) if ui.label(h)=='Mini-AI' and not ui.u.GetWindow(h,4)),None)
            if top:break
            time.sleep(.04)
        if not top:raise AssertionError('Expected native window caption Mini-AI missing')
        time.sleep(.3)
        items=[{'class':ui.classname(h),'text':ui.label(h)} for h in ui.children(top,child.pid)]
        obsolete=[v for v in items if any(x in v['text'] for x in ['Лицензии и исходники','EnterpriseAI —','Документов:','Перетаскивайте'])]
        if obsolete:raise AssertionError('Removed chrome/help/statistics remains visible: '+repr(obsolete))
        icons=[ui.send(top,child.pid,0x7f,kind) for kind in (0,1,2)]
        if not any(icons):raise AssertionError('Native title/taskbar window icon missing')
        screenshot=output.with_suffix('.png');image=ui.capture(top,child.pid,screenshot)
        rgb=np.asarray(image,dtype=np.int16)
        yellow_palette=np.array([[240,235,221],[247,243,231],[250,248,240],[168,153,111]],dtype=np.int16)
        yellow=ui.palette_mask(rgb,yellow_palette)
        purple=ui.palette_mask(rgb,ui.pulse_palette())
        if np.count_nonzero(yellow)<100 or np.count_nonzero(purple)<20:raise AssertionError('Pastel-yellow surface and purple graph theme pixels must both be present')
        stdout,stderr=child.communicate(timeout=12)
        if child.returncode or stderr:raise AssertionError('Branding UI failed: '+repr(stderr))
        report={'success':True,'pid':child.pid,'ordinaryParentPid':os.getpid(),'exe':str(exe),'exeSha256':hashlib.file_digest(exe.open('rb'),'sha256').hexdigest(),'startedUtc':started,'completedUtc':datetime.now(timezone.utc).isoformat(),'exitCode':child.returncode,'stdout':stdout.decode('utf-8'),'mainCaption':'Mini-AI','windowIconPresent':True,'pastelYellowPixels':int(np.count_nonzero(yellow)),'purpleNodePixels':int(np.count_nonzero(purple)),'obsoleteChromeAbsent':True,'controls':items,'ownedWindowScreenshot':str(screenshot),'limitations':['Checks the actual owned HWND icon, not Start-menu icon cache.','Native pixels complement, rather than replace, UI/layout and embedded icon resource tests.','This icon and theme observer does not verify filesystem or registry operations.']}
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    finally:
        if child.poll() is None:
            ui.dismiss_owned_dialogs(child.pid)
            for h in ui.windows(child.pid):ui.send(h,child.pid,0x10)
            try:child.communicate(timeout=4)
            except subprocess.TimeoutExpired:child.kill();child.communicate()
    print(json.dumps({'success':True,'pid':child.pid,'output':str(output)}))

if __name__=='__main__':main()
