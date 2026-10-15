"""Inspect the generated delivery, then exercise CLI without SWT/native DLLs."""
from pathlib import Path
import hashlib
import json
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
resources = root / "build/generated/legal-resources"
legal = resources / "enterpriseai/legal"
catalog = json.loads((legal / "catalog.json").read_text(encoding="utf-8"))
archive_path = legal / "corresponding-source.zip"
with archive_path.open("rb") as stream:
    sha = hashlib.file_digest(stream, "sha256").hexdigest()
assert sha == catalog["correspondingSource"]["sha256"]
with zipfile.ZipFile(archive_path) as archive:
    inventory = json.loads(archive.read("source-inventory.json"))
    for group in ("sources", "recipes"):
        for entry in inventory[group]:
            with archive.open(entry["path"]) as stream:
                assert hashlib.file_digest(stream, "sha256").hexdigest() == entry["sha256"], entry["path"]
    for entry in inventory["notices"]:
        assert hashlib.sha256(archive.read("notices/" + entry["path"])).hexdigest() == entry["sha256"]
    assert any(entry["path"].endswith("patches/org/eclipse/swt/internal/Library.java") for entry in inventory["recipes"])
    assert any(entry["path"].endswith("toolchain/prepare_jdk_static.py") for entry in inventory["recipes"])
    for entry in inventory["recipes"]:
        data = archive.read(entry["path"])
        developer = b"C:" + b"/Users/" + b"Oscar"
        assert developer not in data and developer.replace(b"/", b"\\") not in data, entry["path"]

java = Path("C:/Tools/jdk-25.0.2/bin/java.exe")
classes = root / "build/classes/java/main"
patched = root / "build/legal-test/classes"
classpath = ";".join(map(str, (patched, classes, resources)))
# No SWT JAR, no optional-module resources, no native DLL directory on classpath.
notice_result = subprocess.run([str(java), "-cp", classpath, "ai.enterprise.bootstrap.Main", "--licenses"], check=True, capture_output=True)
assert notice_result.stdout == (legal / "notices.txt").read_bytes()
assert not notice_result.stderr
process = subprocess.Popen([str(java), "-Xmx16m", "-cp", classpath, "ai.enterprise.bootstrap.Main", "--third-party-source-stdout"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
stream_hash = hashlib.file_digest(process.stdout, "sha256").hexdigest()
stderr = process.stderr.read()
assert process.wait() == 0 and not stderr and stream_hash == sha
report = {"sourceSha256": sha, "sourceBytes": archive_path.stat().st_size,
          "sourceEntriesVerified": len(inventory["sources"]) + len(inventory["recipes"]),
          "noticeEntriesVerified": len(inventory["notices"]),
          "withoutSwtJarOrPlatformInitialization": True, "sourceStreamHeapMiB": 16,
          "notes": "Generated-resource/JVM proof; published native EXE must repeat stdout/hash checks. Transient filesystem operations and registry changes remain unverified."}
output = root / "build/test-results/embedded-licenses.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report))
