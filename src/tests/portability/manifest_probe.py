"""Owned EXE manifest experiment; no production file modifications."""
import hashlib, json, os, shutil, subprocess, uuid
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / "src/build/test-results"
base = BUILD / "manifest-probe" / uuid.uuid4().hex
base.mkdir(parents=True)
manifest = base / "EnterpriseAI.manifest"
manifest.write_text('''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
<assemblyIdentity version="1.0.0.0" processorArchitecture="amd64" name="EnterpriseAI.Desktop" type="win32"/>
<dependency><dependentAssembly><assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0" processorArchitecture="amd64" publicKeyToken="6595b64144ccf1df" language="*"/></dependentAssembly></dependency>
<trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges><requestedExecutionLevel level="asInvoker" uiAccess="false"/></requestedPrivileges></security></trustInfo>
<compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1"><application><supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/></application></compatibility>
<application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings>
<dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/pm</dpiAware>
<dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2</dpiAwareness>
<longPathAware xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">true</longPathAware>
</windowsSettings></application></assembly>''',encoding="utf-8")
short = base / "EnterpriseAI.exe"
shutil.copyfile(ROOT / "EnterpriseAI.exe", short)
mt = Path(os.environ["PROGRAMFILES(X86)"]) / "Windows Kits/10/bin/10.0.26100.0/x64/mt.exe"
patch = subprocess.run([str(mt),"-nologo","-manifest",str(manifest),"-outputresource:"+str(short)+";#1"],capture_output=True,text=True)
if patch.returncode:
    raise RuntimeError(patch.stdout+patch.stderr)
folder = base / "Unicode с пробелами Қазақ ә ғ ң"
while len(str(folder / "EnterpriseAI.exe")) < 350:
    folder /= "Следующая папка длинного пути 0123456789"
def wide(path): return "\\\\?\\"+str(path.absolute())
os.makedirs(wide(folder))
exe=folder / "EnterpriseAI.exe"
shutil.copyfile(short,wide(exe));os.mkdir(wide(folder / "native"))
for dll in (ROOT / "native").glob("*.dll"):shutil.copyfile(dll,wide(folder / "native" / dll.name))
def sha(path):return hashlib.sha256(open(str(path),"rb").read()).hexdigest()
original=sha(ROOT / "EnterpriseAI.exe")
run=subprocess.run([wide(exe),"--ui-smoke"],executable=wide(exe),cwd=os.environ["SYSTEMROOT"],capture_output=True,encoding="utf-8",timeout=20)
report={"completed":run.returncode==0,"exitCode":run.returncode,"stdout":run.stdout,"stderr":run.stderr,"exe":str(exe),"productionUnchanged":sha(ROOT / "EnterpriseAI.exe")==original,"experimentalChange":"Owned EXE RT_MANIFEST1 asInvoker/longPathAware/Common-Controls6; no IMM/TSF changes"}
(BUILD / "manifest-probe.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(report,ensure_ascii=True))
