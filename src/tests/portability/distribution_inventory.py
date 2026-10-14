"""Developer observer for the published native application files, never original data."""
from __future__ import annotations
import hashlib, json, os, struct, time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]
REPORT=ROOT / "src/build/test-results/distribution-inventory.json"
ledger=json.loads((ROOT / "licenses/application-files.json").read_text(encoding="utf-8-sig"))
paths=[ROOT / relative for relative in ledger if relative!="licenses\\application-files.json"]
needles=[str(ROOT),str(ROOT).replace("\\","/"),str(Path.home()),str(Path.home()).replace("\\","/"),"C:\\Tools\\","C:/Tools/","\\.gradle\\","/.gradle/","\\.cache\\codex-runtimes\\"]
def imported_libraries(data):
    pe=struct.unpack_from("<I",data,0x3c)[0]
    if data[pe:pe+4]!=b"PE\0\0":raise ValueError("Not a PE file")
    count=struct.unpack_from("<H",data,pe+6)[0]
    optional_size=struct.unpack_from("<H",data,pe+20)[0]
    optional=pe+24;magic=struct.unpack_from("<H",data,optional)[0]
    directory=optional+(112 if magic==0x20b else 96)
    import_rva=struct.unpack_from("<I",data,directory+8)[0]
    sections=[]
    for i in range(count):
        virtual_size,address,raw_size,raw=struct.unpack_from("<IIII",data,optional+optional_size+i*40+8)
        sections.append((address,max(virtual_size,raw_size),raw))
    def offset(rva):
        for address,size,raw in sections:
            if address<=rva<address+size:return raw+rva-address
        raise ValueError("Unmapped PE import address")
    if not import_rva:return []
    descriptor=offset(import_rva);result=[]
    while any(data[descriptor:descriptor+20]):
        name=offset(struct.unpack_from("<I",data,descriptor+12)[0])
        end=data.index(b"\0",name)
        result.append(data[name:end].decode("ascii"))
        descriptor+=20
    return result

files=[];matches=[];missing_runtime=[]
for path in sorted(paths):
    data=path.read_bytes()
    row={"path":str(path.relative_to(ROOT)),"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest()}
    if path.suffix.lower() in (".exe",".dll"):
        row["peImportedLibraries"]=imported_libraries(data)
        for dependency in row["peImportedLibraries"]:
            if dependency.lower().startswith(("vcruntime","msvcp","concrt","mfc")):
                if not any(candidate.name.lower()==dependency.lower() for candidate in paths if candidate.parent==path.parent):
                    missing_runtime.append({"importer":row["path"],"library":dependency})
        hits=[]
        binary_lower=data.lower()
        for needle in needles:
            for encoding in ("utf-8","utf-16-le"):
                if needle.encode(encoding).lower() in binary_lower:hits.append({"pathPattern":needle,"encoding":encoding})
        row["developerAbsolutePathMatches"]=hits
        if hits:matches.append(row["path"])
    files.append(row)
report={"completed":not matches and not missing_runtime,"observedAtUtc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
    "profile":"GraalVM CE25.0.2+10.1 Native Image, Java25, prebuilt JNI DLLs, no extraction",
    "files":files,"totalApplicationBytes":sum(item["bytes"] for item in files),"developerPathMatchedFiles":matches,"missingAppLocalMicrosoftRuntime":missing_runtime,
    "dataDirectoriesIncluded":False,"limitations":["Byte scan covers listed local path patterns; it cannot prove absence of every unknown developer path","File inventory and hash identify exact artifact revision, not clean-Windows or Process Monitor acceptance","No model/weights present; model preparation and inference acceptance remain separate"]}
REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({"completed":report["completed"],"files":len(files),"bytes":report["totalApplicationBytes"],"developerPathMatchedFiles":matches}))
raise SystemExit(0 if report["completed"] else 1)
