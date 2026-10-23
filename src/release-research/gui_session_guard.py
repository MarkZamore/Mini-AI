"""Hold the shared GPU lease while real final GUI demonstrations are captured.

Does not launch/control windows. The UI is operated separately with Computer Use.
Writes resource observations, not response latency or quality measurements.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid

import psutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(os.path.abspath(args.workspace))
    output = Path(os.path.abspath(args.output))
    output.relative_to(root)
    if output.exists():
        raise ValueError("New output required")
    research = root / "src/build/research-20261024"
    lock = research / "gpu.lock"
    nonce = uuid.uuid4().hex
    lease = {"pid": os.getpid(), "owner": "root-final-gui", "task": "Five actual native GUI demonstrations", "nonce": nonce}
    fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(lease, stream)
    try:
        output.mkdir(parents=True, exist_ok=False)
        (output / "lease.json").write_text(json.dumps(lease, indent=2), encoding="utf-8")
        print(json.dumps({"state": "gui-lease-held", "output": str(output), **lease}), flush=True)
        nvidia = shutil.which("nvidia-smi")
        with (output / "resources.jsonl").open("x", encoding="utf-8") as log:
            while True:
                apps, teams = [], []
                for process in psutil.process_iter(["pid", "name", "exe", "memory_info"]):
                    try:
                        name = (process.info["name"] or "").lower()
                        if name not in ("mini-ai.exe", "inference-worker.exe", "ms-teams.exe"):
                            continue
                        exe = process.info["exe"] or ""
                        row = {"pid": process.info["pid"], "name": name, "exe": exe,
                               "rss": process.info["memory_info"].rss}
                        if name == "ms-teams.exe":
                            teams.append(row)
                        else:
                            apps.append(row)
                    except (psutil.AccessDenied, psutil.NoSuchProcess):
                        continue
                row = {"utc": datetime.now(timezone.utc).isoformat(), "available_ram": psutil.virtual_memory().available,
                       "apps": apps, "teams": teams}
                if nvidia:
                    try:
                        result = subprocess.run([nvidia, "--query-gpu=name,memory.total,memory.used,memory.free",
                            "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=4,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                        row["gpu_csv"] = result.stdout.strip()
                        row["gpu_query_exit"] = result.returncode
                    except (OSError, subprocess.TimeoutExpired) as error:
                        row["gpu_query_error"] = repr(error)
                log.write(json.dumps(row, ensure_ascii=False) + "\n")
                log.flush()
                if (output / "finish-requested").exists() and not apps:
                    break
                time.sleep(1)
    finally:
        if lock.exists() and json.loads(lock.read_text(encoding="utf-8")).get("nonce") == nonce:
            lock.unlink()
    (output / "complete.json").write_text(json.dumps({"state": "all-gui-processes-closed", "lease_released": True}), encoding="utf-8")
    print("Final GUI lease released", flush=True)


if __name__ == "__main__":
    main()
