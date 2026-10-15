"""Build-time-only, deterministic embedded notices and complete source delivery.

No dependency on a previous published licenses directory. All outputs are under
src/build; the runtime streams the ZIP resource and never extracts it.
"""
from pathlib import Path
import hashlib
import json
import os
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/generated/legal-resources/enterpriseai/legal"
CACHE = Path(os.environ.get("ENTERPRISE_BUILD_SHARED_ROOT", str(ROOT / "build"))).resolve()
JDK = CACHE / "tools/graalvm-community-openjdk-25.0.2+10.1"
PINS = json.loads((ROOT / "toolchain/static-sources.json").read_text())


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def make_info(name, compression=zipfile.ZIP_DEFLATED):
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = compression
    info.external_attr = 0o100644 << 16
    return info


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    modules = sorted(p.parent for p in (ROOT / "modules").glob("*/module.json"))
    module_ids = [json.loads((p / "module.json").read_text())["id"] for p in modules]
    text_files = {}
    for name in ("LICENSE.txt", "LICENSE_NATIVEIMAGE.txt", "THIRD_PARTY_LICENSE.txt", "GRAALVM-README.md"):
        text_files["GraalVM-CE/" + name] = (JDK / name).read_bytes()
    for file in sorted((JDK / "legal").rglob("*")):
        if file.is_file():
            data = file.read_bytes()
            # Windows JDK legal aliases can be materialized symbolic-link targets.
            if len(data) < 150 and data.strip().startswith(b"../"):
                target = (file.parent / data.decode().strip()).resolve()
                if not target.is_relative_to(JDK.resolve()):
                    raise ValueError("Unsafe JDK legal alias")
                data = target.read_bytes()
            text_files["GraalVM-CE/legal/" + file.relative_to(JDK / "legal").as_posix()] = data
    text_files["SWT/EPL-2.0.txt"] = (CACHE / "dependencies/EPL-2.0.txt").read_bytes()
    with zipfile.ZipFile(CACHE / "dependencies/swt-3.135.0.jar") as jar:
        for name in sorted(jar.namelist()):
            if not name.endswith("/") and (name == "about.html" or name.startswith("about_files/")):
                text_files["SWT/" + name] = jar.read(name)
    for module in modules:
        for file in sorted((module / "licenses").rglob("*")):
            if file.is_file():
                text_files[module.name + "/" + file.relative_to(module / "licenses").as_posix()] = file.read_bytes()
    # Static CRT redistribution conditions apply even though no CRT DLL is shipped.
    crt = ROOT / "licenses/Microsoft-VC-Runtime/LICENSE.txt"
    if not crt.exists():
        # Source-controlled copy is made once when implementing this packaging helper.
        raise FileNotFoundError(crt)
    text_files["Microsoft-VC-Runtime/LICENSE.txt"] = crt.read_bytes()
    for file in sorted(crt.parent.glob("*.txt")):
        text_files["Microsoft-VC-Runtime/" + file.name] = file.read_bytes()

    archives = []
    for component in ("graal", "labs-openjdk"):
        pin = PINS[component]
        source = CACHE / "static-toolchain/downloads" / f"{component}-{pin['revision']}.zip"
        if digest(source) != pin["archiveSha256"]:
            raise ValueError("Pinned upstream source digest mismatch: " + component)
        archives.append(("upstream/" + source.name, source, pin))
    archives.append(("upstream/swt-3.135.0-sources.jar", CACHE / "dependencies/swt-3.135.0-sources.jar", {"revision": "3.135.0", "repository": "eclipse-platform/eclipse.platform.swt"}))
    if "database" in module_ids:
        sqlite_candidates = sorted((CACHE / "vendor").glob("*sqlite*.zip"))
        sqlite_source = next(p for p in sqlite_candidates if digest(p) == "36ad6e7f38540a3b21a2ac36340833f0a9e426bc1c752751c3ba669466827eae")
        archives.append(("upstream/sqlite-amalgamation-3530100.zip", sqlite_source, {"revision": "3.53.1", "repository": "https://www.sqlite.org/2026/sqlite-amalgamation-3530100.zip"}))
    recipes = []
    for top in ("toolchain", "build-logic", "patches", "core", "licenses", "gradle"):
        recipes += [("enterpriseai/" + p.relative_to(ROOT).as_posix(), p) for p in (ROOT / top).rglob("*") if p.is_file() and "__pycache__" not in p.parts]
    for module in modules:
        recipes += [("enterpriseai/" + p.relative_to(ROOT).as_posix(), p) for p in module.rglob("*") if p.is_file() and "build" not in p.relative_to(module).parts and "__pycache__" not in p.parts]
    for name in ("build.ps1", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"):
        recipes.append(("enterpriseai/" + name, ROOT / name))
    # Snapshot small local sources once so concurrent development cannot produce
    # an archive whose inventory describes earlier bytes of the same file.
    recipe_data = {name: path.read_bytes() for name, path in sorted(recipes)}

    inventory = {"schemaVersion": 1, "profile": "windows-x64-static", "modules": module_ids, "pins": PINS,
                 "sources": [{"path": name, "bytes": path.stat().st_size, "sha256": digest(path), **pin} for name, path, pin in archives],
                 "recipes": [{"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, data in recipe_data.items()],
                 "notices": [{"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, data in sorted(text_files.items())]}
    explanation = ("миниИИ dependency materials\n\n"
                   "GraalVM Community/OpenJDK: GPLv2 with Classpath Exception and component notices.\n"
                   "SWT 3.135.0: EPL 2.0; unmodified upstream sources and EnterpriseAI changes are included.\n"
                   "Microsoft CRT: statically linked, no redistributable DLL is supplied.\n"
                   "Optional component notices are included only for present modules.\n"
                   "No licence for the owner's complete application is inferred from dependency licences.\n"
                   "Model workers and weights are separate distributions with their own provenance.\n\n"
                   "Complete corresponding source ZIP is embedded. Run миниИИ.exe --third-party-source-stdout\n"
                   "to stream it without extraction. The calling program may save the output explicitly.\n"
                   "The ZIP contains pinned upstream source archives, all applied patch/build recipes,\n"
                   "the included module sources, component licence texts and SHA-256 inventory.\n")
    notices = explanation + "".join("\n\n===== " + name + " =====\n" + data.decode("utf-8", errors="replace") for name, data in sorted(text_files.items()))
    archive_path = OUTPUT / "corresponding-source.zip"
    with zipfile.ZipFile(archive_path, "w", allowZip64=True) as archive:
        archive.writestr(make_info("README.txt"), explanation)
        for name, data in sorted(text_files.items()):
            archive.writestr(make_info("notices/" + name), data)
        for name, path, _ in archives:
            with archive.open(make_info(name, zipfile.ZIP_STORED), "w", force_zip64=True) as output, path.open("rb") as input:
                while block := input.read(1024 * 1024):
                    output.write(block)
        for name, data in recipe_data.items():
            archive.writestr(make_info(name), data)
        archive.writestr(make_info("source-inventory.json"), json.dumps(inventory, ensure_ascii=False, sort_keys=True, indent=2))
    inventory["correspondingSource"] = {"path": "corresponding-source.zip", "bytes": archive_path.stat().st_size, "sha256": digest(archive_path)}
    (OUTPUT / "notices.txt").write_text(notices, encoding="utf-8", newline="\n")
    (OUTPUT / "catalog.json").write_text(json.dumps(inventory, ensure_ascii=False, sort_keys=True, indent=2), encoding="utf-8", newline="\n")
    # The removed legal GUI was the only reader of this redundant text resource.
    # Corresponding sources and all applied changes remain in the streamed ZIP.
    (OUTPUT / "changes.txt").unlink(missing_ok=True)
    print(json.dumps({"sourceBytes": archive_path.stat().st_size, "sourceSha256": inventory["correspondingSource"]["sha256"], "modules": module_ids}))


if __name__ == "__main__":
    main()
