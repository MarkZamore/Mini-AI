"""Fetch pinned build-only sources. Never operates on docs, db or model.

Run with Python -B. Source archives and all extracted files live in src/build.
The recorded archive digest is provenance, not an upstream signature.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import urllib.request
import zipfile

SOURCE_ROOT = Path(__file__).resolve().parents[1]
BUILD_ROOT = SOURCE_ROOT / "build" / "static-toolchain"
PINS = json.loads((Path(__file__).with_name("static-sources.json")).read_text())


def prepare(name):
    pin = PINS[name]
    revision = pin["revision"]
    url = f"https://codeload.github.com/{pin['repository']}/zip/{revision}"
    archive = BUILD_ROOT / "downloads" / f"{name}-{revision}.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        partial = archive.with_suffix(".part")
        request = urllib.request.Request(url, headers={"User-Agent": "EnterpriseAI-build"})
        with urllib.request.urlopen(request, timeout=180) as response, partial.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        partial.replace(archive)
    with archive.open("rb") as input_file:
        digest = hashlib.file_digest(input_file, "sha256").hexdigest()
    if digest != pin["archiveSha256"]:
        raise ValueError(f"Pinned source archive checksum mismatch: {name}")
    destination = BUILD_ROOT / "sources" / name
    marker = destination / ".enterpriseai-source.json"
    if marker.exists():
        recorded = json.loads(marker.read_text())
        if recorded.get("revision") != revision or recorded.get("archiveSha256") != digest:
            raise ValueError(f"Extracted source provenance mismatch: {name}")
    if not marker.exists():
        destination.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as contents:
            for entry in contents.infolist():
                pieces = Path(entry.filename).parts[1:]
                if not pieces:
                    continue
                relative = Path(*pieces)
                target = destination / relative
                if not target.resolve().is_relative_to(destination.resolve()):
                    raise ValueError(f"Unsafe archive member: {entry.filename}")
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with contents.open(entry) as source, target.open("wb") as output:
                        while chunk := source.read(1024 * 1024):
                            output.write(chunk)
        marker.write_text(json.dumps({**pin, "url": url, "archiveSha256": digest}, indent=2))
    return {"name": name, "source": str(destination), "revision": revision, "archiveSha256": digest}


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=2) as pool:
        for result in pool.map(prepare, ("graal", "labs-openjdk")):
            print(json.dumps(result), flush=True)
