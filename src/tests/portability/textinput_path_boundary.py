"""Developer-only Windows UI path boundary measurement; copies only application files."""
import json, os, shutil, subprocess, uuid
from pathlib import Path
root=Path(__file__).resolve().parents[3]
build=root / "src/build/test-results"
base=build / "textinput-path-boundary" / uuid.uuid4().hex
base.mkdir(parents=True)
cases=[]
for length in (252,253,254,255,256,257,258,259,260,261,270,300):
    name_length=length-len(str(base))-len("\\EnterpriseAI.exe")-1
    folder=base / ("x"*name_length)
    def wide(path):return "\\\\?\\"+str(path.absolute())
    os.makedirs(wide(folder));os.mkdir(wide(folder / "native"))
    exe=folder / "EnterpriseAI.exe"
    assert len(str(exe))==length
    shutil.copyfile(root / "EnterpriseAI.exe",wide(exe))
    for dll in (root / "native").glob("*.dll"):shutil.copyfile(dll,wide(folder / "native" / dll.name))
    child=subprocess.run([wide(exe),"--ui-smoke"],executable=wide(exe),cwd=os.environ["SYSTEMROOT"],capture_output=True,encoding="utf-8",timeout=15)
    cases.append({"exeCharacters":length,"exitCode":child.returncode,"stdout":child.stdout,"stderr":child.stderr})
report={"completed":True,"scope":"Owned copies, unchanged original EXE/DLLs, actual EXE length varies","cases":cases}
(build / "textinput-path-boundary.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
print(json.dumps(report))
