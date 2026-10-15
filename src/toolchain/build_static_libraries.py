"""Build real /MT JNI archives for the one-EXE profile, not import libraries."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
build = root / "build/static-toolchain"
jdk = build / "graalvm-static"
out = build / "application-native"
out.mkdir(parents=True, exist_ok=True)
swt = build / "sources/swt/library"


def build_icon_resource():
    application_icon = root / "core/shell/resources/enterpriseai/ui/application.ico"
    if not application_icon.is_file():
        raise FileNotFoundError("The application's single source icon is missing")
    # Resource 101 is the first EXE icon and retains SWT's search-icon ABI.
    # The window reads this same source through its embedded Java resource.
    icon_files = [application_icon, swt / "cancel.ico", swt / "search_darktheme.ico", swt / "cancel_darktheme.ico"]
    resource = out / "swt-icons.rc"
    resource.write_text('#pragma code_page(65001)\n' + "\n".join(f'{101+i} ICON "{file.as_posix()}"'
        for i, file in enumerate(icon_files)), encoding="utf-8")
    resource_binary = out / "swt-icons.res"
    resource_compiler = Path("C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64/rc.exe")
    subprocess.run([str(resource_compiler), "/nologo", "/fo", str(resource_binary), str(resource)], check=True)
    inventory = {"path": str(resource_binary), "sha256": hashlib.sha256(resource_binary.read_bytes()).hexdigest(),
        "sourcePath": "core/shell/resources/enterpriseai/ui/application.ico",
        "sourceSha256": hashlib.sha256(application_icon.read_bytes()).hexdigest(), "resourceGroup": 101}
    (out / "swt-icons.json").write_text(json.dumps(inventory, indent=2), encoding="utf-8")
    return inventory


if len(sys.argv) > 1:
    if sys.argv[1:] != ["--resources-only"]:
        raise SystemExit("Usage: build_static_libraries.py [--resources-only]")
    print(json.dumps({"completed": True, "resource": build_icon_resource()}))
    raise SystemExit(0)

with zipfile.ZipFile(root / "build/dependencies/swt-3.135.0-sources.jar") as archive:
    for member in archive.namelist():
        if member.startswith("library/"):
            archive.extract(member, swt.parent)
# Preserve upstream headers, callbacks and JNI ABI; only change static initialization/loading.
file = swt / "swt.c"
text = file.read_text().replace('#include "swt.h"', '#include "swt.h"\n#include <windows.h>\nextern HINSTANCE g_hInstance;')
text = text.replace("JNI_OnLoad(", "JNI_OnLoad_enterpriseai_swt(")
text = text.replace("JVM = vm;", "JVM = vm;\n\tg_hInstance = GetModuleHandleW(NULL);")
file.write_text(text)
file = swt / "swt.h"
text = file.read_text().replace('LoadLibraryA(name##_LIB)', 'LoadLibraryExA(name##_LIB, NULL, LOAD_LIBRARY_SEARCH_SYSTEM32)')
file.write_text(text)
file = swt / "defines.h"
file.write_text(file.read_text() + "\n/* No browser backend or non-inbox loader in the standalone profile. */\n#define NO_CreateCoreWebView2EnvironmentWithOptions\n")
# SWT's DLL-local helper collides with Graal's static JVM helper in one EXE.
file = swt / "callback.c"
file.write_text(re.sub(r"\binitialize\b", "enterpriseai_swt_callback_initialize", file.read_text()))

msvc = Path("C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231")
sdk = Path("C:/Program Files (x86)/Windows Kits/10")
sdk_version = "10.0.26100.0"
bin_dir = msvc / "bin/Hostx64/x64"
env = dict(os.environ)
env["INCLUDE"] = ";".join(str(p) for p in [msvc / "include", sdk / f"Include/{sdk_version}/ucrt",
    sdk / f"Include/{sdk_version}/um", sdk / f"Include/{sdk_version}/shared"])
env["LIB"] = ";".join(str(p) for p in [msvc / "lib/x64", sdk / f"Lib/{sdk_version}/ucrt/x64",
    sdk / f"Lib/{sdk_version}/um/x64"])
env["VSLANG"] = "1033"


def compile_file(spec):
    name, file, flags = spec
    folder = out / name
    folder.mkdir(exist_ok=True)
    object_file = folder / (file.stem + ".obj")
    command = [str(bin_dir / "cl.exe"), "/nologo", "/c", "/O1", "/MT",
        "/I" + str(jdk / "include"), "/I" + str(jdk / "include/win32"), *flags,
        "/Fo" + str(object_file), str(file)]
    result = subprocess.run(command, env=env, capture_output=True)
    (folder / (file.stem + ".log")).write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f"Static compile failed: {file}; see {folder / (file.stem + '.log')}")
    return name, object_file


specs = []
swt_names = "swt callback c c_stats os os_structs os_custom os_stats com_structs com com_stats com_custom osversion osversion_structs osversion_stats gdip gdip_structs gdip_stats gdip_custom".split()
for name in swt_names:
    file = next(p for p in (swt / (name + ".c"), swt / (name + ".cpp")) if p.exists())
    specs.append(("enterpriseai_swt", file, ["/I" + str(swt), "/DNDEBUG", "/DUNICODE", "/D_UNICODE",
        "/DUSE_ASSEMBLER", "/D_WIN32_WINNT=0x0A00", "/DWINVER=0x0A00"]))
platform = out / "platform_onload.c"
platform.write_text("#include <jni.h>\nJNIEXPORT jint JNICALL JNI_OnLoad_enterpriseai_platform(JavaVM *vm, void *reserved) { (void)vm; (void)reserved; return JNI_VERSION_1_8; }\n")
specs += [("enterpriseai_platform", root / "core/platform-windows/native/platform.c", []),
    ("enterpriseai_platform", platform, [])]
if (root / "modules/database/module.json").exists():
    sqlite = root / "build/vendor/sqlite-amalgamation-3530100"
    flags = ["/I" + str(sqlite), "/DSQLITE_ENABLE_FTS5", "/DSQLITE_TEMP_STORE=3", "/DSQLITE_OMIT_LOAD_EXTENSION",
        "/DSQLITE_THREADSAFE=1", "/DSQLITE_OS_WIN=1", "/DJNI_OnLoad=JNI_OnLoad_enterpriseai_sqlite"]
    specs += [("enterpriseai_sqlite", root / "modules/database/native/sqlite_jni.c", flags),
        ("enterpriseai_sqlite", sqlite / "sqlite3.c", flags)]
objects = {}
with ThreadPoolExecutor(max_workers=4) as pool:
    for name, object_file in pool.map(compile_file, specs):
        objects.setdefault(name, []).append(object_file)
reports = []
for name, files in objects.items():
    archive = out / (name + ".lib")
    archive.unlink(missing_ok=True)
    subprocess.run([str(bin_dir / "lib.exe"), "/nologo", "/OUT:" + str(archive), *map(str, files)], env=env, check=True)
    directives = subprocess.check_output([str(bin_dir / "dumpbin.exe"), "/directives", str(archive)], text=True)
    (out / (name + "-directives.txt")).write_text(directives)
    if re.search(r"DEFAULTLIB:[\"']?(MSVCRT[D]?|MSVCPRT[D]?)\b", directives, re.I):
        raise RuntimeError(f"Dynamic CRT directive in {name}")
    members = subprocess.check_output([str(bin_dir / "dumpbin.exe"), "/linkermember:2", str(archive)], text=True)
    exports = sorted(set(re.findall(r"\b(?:Java_\w+|JNI_OnLoad_\w+)\b", members)))
    if "JNI_OnLoad_" + name not in exports:
        raise RuntimeError(f"Missing builtin JNI initializer for {name}")
    reports.append({"name": name, "path": str(archive), "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
        "crt": "/MT", "exports": exports})
resource_inventory = build_icon_resource()
(out / "inventory.json").write_text(json.dumps(reports, indent=2))
print(json.dumps({"completed": True, "archives": [p["name"] for p in reports], "crt": "/MT", "resource": resource_inventory}))
