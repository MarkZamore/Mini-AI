"""Build Graal's pinned Windows JVM shim with /MT, never rewrite original tools.

Fallback generation follows the upstream JvmFuncsFallbacksBuildTask algorithm.
Original shim symbols are used as the ABI inventory for the identical pinned JDK.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess

root = Path(__file__).resolve().parents[1]
build = root / "build/static-toolchain"
original = root / "build/tools/graalvm-community-openjdk-25.0.2+10.1"
target = build / "graalvm-static"
source = build / "sources/graal/substratevm/src/com.oracle.svm.native.jvm.windows/src"
output = build / "svm-jvm"
output.mkdir(parents=True, exist_ok=True)
msvc = Path("C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231")
sdk = Path("C:/Program Files (x86)/Windows Kits/10")
sdk_version = "10.0.26100.0"
bin_dir = msvc / "bin/Hostx64/x64"
symbols = subprocess.check_output([str(bin_dir / "dumpbin.exe"), "/linkermember:2",
    str(original / "lib/svm/clibraries/windows-amd64/jvm.lib")], text=True)
def jvm_symbols(output):
    # Archive-member headings can truncate long names; only the indexed symbol table is ABI.
    return set(re.findall(r"^[ \t]+[0-9A-Fa-f]+[ \t]+(JVM_[A-Za-z0-9_]+)[ \t]*$", output, re.M))

all_symbols = jvm_symbols(symbols)
if not all_symbols:
    raise RuntimeError("Pinned shim's indexed JVM symbol table is empty")
implementations = set(re.findall(r"JNIEXPORT[\w\s*]+?JNICALL\s+(JVM_\w+)\s*\(",
    (source / "JvmFuncs.c").read_text()))
fallbacks = sorted(all_symbols - implementations)
no_env = set("""JVM_GC JVM_ActiveProcessorCount JVM_GetInterfaceVersion JVM_GetManagement
JVM_IsSupportedJNIVersion JVM_MaxObjectInspectionAge JVM_NativePath JVM_ReleaseUTF
JVM_SupportsCX8 JVM_BeforeHalt JVM_Halt JVM_LoadLibrary JVM_UnloadLibrary JVM_FindLibraryEntry
JVM_FindSignal JVM_RaiseSignal JVM_RegisterSignal JVM_FreeMemory JVM_MaxMemory JVM_TotalMemory
JVM_RawMonitorCreate JVM_RawMonitorDestroy JVM_RawMonitorEnter JVM_RawMonitorExit""".split())
generated = output / "JvmFuncsFallbacks.c"
lines = ["/* Generated using the pinned upstream fallback algorithm. */", "#include <stdlib.h>",
    "#include <stdio.h>", "#include <jni.h>"]
for name in fallbacks:
    if name in no_env:
        lines.append(f'JNIEXPORT void JNICALL {name}() {{ fprintf(stderr, "{name} called: Unimplemented\\n"); abort(); }}')
    else:
        lines.append(f'JNIEXPORT jobject JNICALL {name}(JNIEnv *env) {{ (*env)->FatalError(env, "{name} called: Unimplemented"); return NULL; }}')
generated.write_text("\n".join(lines) + "\n")
env = dict(os.environ)
env["INCLUDE"] = ";".join(str(p) for p in [msvc / "include", sdk / f"Include/{sdk_version}/ucrt",
    sdk / f"Include/{sdk_version}/um", sdk / f"Include/{sdk_version}/shared"])
env["LIB"] = ";".join(str(p) for p in [msvc / "lib/x64", sdk / f"Lib/{sdk_version}/ucrt/x64",
    sdk / f"Lib/{sdk_version}/um/x64"])
objects = []
for file in [source / "JvmFuncs.c", generated]:
    object_file = output / (file.stem + ".obj")
    subprocess.run([str(bin_dir / "cl.exe"), "/nologo", "/c", "/O2", "/MT", "/DJNIEXPORT=",
        "/I" + str(original / "include"), "/I" + str(original / "include/win32"),
        "/Fo" + str(object_file), str(file)], env=env, check=True)
    objects.append(object_file)
archive = target / "lib/svm/clibraries/windows-amd64/jvm.lib"
if not archive.resolve().is_relative_to(build.resolve()):
    raise ValueError("Shim output is outside isolated build directory")
archive.unlink(missing_ok=True)
subprocess.run([str(bin_dir / "lib.exe"), "/nologo", "/OUT:" + str(archive),
    *map(str, objects)], env=env, check=True)
directives = subprocess.check_output([str(bin_dir / "dumpbin.exe"), "/directives", str(archive)], text=True)
(output / "directives.txt").write_text(directives)
if re.search(r"DEFAULTLIB:[\"']?(MSVCRT|MSVCRTD)\b", directives, re.I):
    raise RuntimeError("Dynamic CRT directive remains in rebuilt SVM shim")
rebuilt_symbols = jvm_symbols(subprocess.check_output([str(bin_dir / "dumpbin.exe"), "/linkermember:2", str(archive)], text=True))
if rebuilt_symbols != all_symbols:
    raise RuntimeError(f"Rebuilt shim ABI mismatch: missing={sorted(all_symbols - rebuilt_symbols)} extra={sorted(rebuilt_symbols - all_symbols)}")
with archive.open("rb") as input_file:
    digest = hashlib.file_digest(input_file, "sha256").hexdigest()
report = {"archive": str(archive), "sha256": digest, "crt": "/MT", "fallbacks": fallbacks,
    "jvmSymbolCount": len(all_symbols), "originalJvmAbiMatched": True,
    "sourceRevision": "981ecb6e1be6bf4591a6dd24b48ea053221209f5"}
(output / "report.json").write_text(json.dumps(report, indent=2))
print(json.dumps({"archive": str(archive), "crt": "/MT", "fallbackCount": len(fallbacks)}))
