"""Developer observer: compile separately, run actual native EXE at a long Unicode path."""
from __future__ import annotations
import hashlib, json, os, shutil, subprocess, time, uuid
from pathlib import Path

project = Path(__file__).resolve().parents[3]
build = project / "src/build/test-results"
folder = build / "styledtext-long" / uuid.uuid4().hex / "Қазақ ә ғ ң Unicode с пробелами 😀"
while len(str(folder / "StyledTextLongPathProbe.exe")) < 380:
    folder /= "Следующая папка длинного пути 0123456789"
def wide(path):
    value = str(path.absolute())
    return value if value.startswith("\\\\?\\") else "\\\\?\\" + value
os.makedirs(wide(folder))
exe = folder / "StyledTextLongPathProbe.exe"
shutil.copyfile(str(build / "styledtext-probe/StyledTextLongPathProbe.exe"), wide(exe))
os.mkdir(wide(folder / "native"))
for dll in (project / "native").glob("swt-*.dll"):
    shutil.copyfile(str(dll), wide(folder / "native" / dll.name))
def inventory():
    return {os.path.relpath(str(Path(root) / name),wide(folder)):hashlib.sha256(open(str(Path(root)/name),"rb").read()).hexdigest()
        for root,_,names in os.walk(wide(folder)) for name in names}
before = inventory()
env = dict(os.environ)
for name in ("JAVA_HOME","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","PYTHONHOME","PYTHONPATH"):
    env.pop(name,None)
env["PATH"] = os.path.join(env["SYSTEMROOT"],"System32")
started = time.perf_counter()
child = subprocess.run([wide(exe)],executable=wide(exe),cwd=env["SYSTEMROOT"],env=env,capture_output=True,encoding="utf-8",timeout=20)
lines = [json.loads(line) for line in child.stdout.splitlines() if line.strip()]
report = {"completed":child.returncode==0 and bool(lines) and lines[-1].get("passed") is True,
    "exitCode":child.returncode,"stderr":child.stderr,"stages":lines,"exe":str(exe),"durationMs":round((time.perf_counter()-started)*1000),
    "fixtureUnchanged":before==inventory(),"limitations":["No global clipboard change","WM_CHAR and native focus checked; no physical keyboard or Asian IME composition test","File snapshot can miss transient writes; not Process Monitor or registry qualification"]}
(build / "styledtext-native-longpath.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(report,ensure_ascii=True))
raise SystemExit(0 if report["completed"] and report["fixtureUnchanged"] else 1)
