"""Bounded resumable download of pinned public release files; preparation only."""
import concurrent.futures
import hashlib
import json
import pathlib
import sys
import time
import urllib.request

root = pathlib.Path(sys.argv[1])
assets = json.loads((root / "release-assets.json").read_text(encoding="utf-8-sig"))
step = 4 * 1024 * 1024

def fetch(task):
    asset, start, end, part = task
    if part.is_file() and part.stat().st_size == end - start + 1:
        return
    for attempt in range(5):
        try:
            req = urllib.request.Request(asset["browser_download_url"] + "?download=1",
                                         headers={"Range": f"bytes={start}-{end}", "User-Agent": "Mini-AI-local-preparation"})
            with urllib.request.urlopen(req, timeout=45) as response:
                expected = f"bytes {start}-{end}/{asset['size']}"
                if response.status != 206 or response.headers.get("Content-Range") != expected:
                    raise RuntimeError(f"Range not honored: {response.status} {response.headers.get('Content-Range')}")
                data = response.read(end - start + 2)
                if len(data) != end - start + 1:
                    raise RuntimeError("Truncated range")
                part.write_bytes(data)
            print(f"{asset['name']} {end + 1}/{asset['size']}", flush=True)
            return
        except Exception:
            if attempt == 4:
                raise
            time.sleep(attempt + 1)

for asset in assets:
    target = root / "downloads" / asset["name"]
    expected_hash = asset["digest"].removeprefix("sha256:")
    if target.is_file() and target.stat().st_size == asset["size"] and hashlib.file_digest(target.open("rb"), "sha256").hexdigest() == expected_hash:
        continue
    parts = root / "downloads" / (asset["name"] + ".parts")
    parts.mkdir(parents=True, exist_ok=True)
    tasks = [(asset, start, min(start + step, asset["size"]) - 1, parts / f"{start:012d}.part")
             for start in range(0, asset["size"], step)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(fetch, tasks))
    with target.open("wb") as output:
        for _, _, _, part in tasks:
            output.write(part.read_bytes())
    with target.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected_hash:
            raise RuntimeError("Release SHA-256 mismatch")
    print(f"Verified {asset['name']} {expected_hash}", flush=True)
