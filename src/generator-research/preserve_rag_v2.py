"""Reconstruct, compile and SHA-prove old v2 bytes only in isolated copies.

This is explicitly a reconstruction after the experiment, not a contemporaneous
source or prompt capture. The live build/classpath is never overwritten.
"""
from __future__ import annotations
import json
from pathlib import Path
import re
import shutil
import subprocess
from common import ROOT, digest, write_json


def main():
    pair = ROOT / 'src/build/research-20261024/generator/dev-rag20/paired-v2'
    destination = pair / 'source-preserved'
    if destination.exists():
        raise FileExistsError('Preserve previous reconstruction')
    if shutil.disk_usage(ROOT.anchor).free < 20 * 1024**3:
        raise OSError('Preserve 20 GiB system-volume reserve')
    destination.mkdir()
    source_paths = []
    for folder in (ROOT / 'src/core', ROOT / 'src/modules'):
        for path in folder.rglob('*'):
            parts = path.relative_to(folder).parts
            if path.is_file() and ('tests' not in parts) and (path.suffix == '.java' or 'resources' in parts or path.name == 'module.json'):
                source_paths.append(path)
    source_paths += list((ROOT / 'src/build/generated/java').rglob('*.java'))
    source_paths += [ROOT / name for name in ('src/build.gradle.kts', 'src/settings.gradle.kts', 'src/gradle.properties',
                                            'src/research/java/ai/enterprise/research/RagEvaluation.java')]
    source_inventory = []
    for path in sorted(source_paths):
        if not path.is_file():
            continue
        target = destination / 'source' / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        source_inventory.append({'path': path.relative_to(ROOT).as_posix(), 'copied_current_sha256': digest(path)})
    policy = destination / 'source/src/modules/assistant/service/ai/enterprise/assistant/AnswerPolicy.java'
    text = policy.read_bytes()
    expression = rb'(?m)^    static final String UNVERIFIED_RESPONSE = [^\n]*\n(?:        \+[^\n]*\n){2}'
    restored, count = re.subn(expression, b'', text)
    if count != 1:
        raise ValueError('Expected exactly the new three-line constant block')
    policy.write_bytes(restored)
    expected_policy = 'fefd04a5a86a5e9f53252d52a8277e28b7a9c167bf4204f11e9f5e72bbaf4952'
    if digest(policy) != expected_policy:
        raise ValueError('Reconstructed AnswerPolicy source differs from recorded v2 SHA')
    assistant = destination / 'source/src/modules/assistant/service/ai/enterprise/assistant/AssistantModule.java'
    text = assistant.read_bytes()
    if text.count(b'AnswerPolicy.UNVERIFIED_RESPONSE') != 2:
        raise ValueError('Expected only the two fallback replacements')
    assistant.write_bytes(text.replace(b'AnswerPolicy.UNVERIFIED_RESPONSE', b'AnswerPolicy.INSUFFICIENT'))
    gate = destination / 'source/src/modules/assistant/service/ai/enterprise/assistant/NormativeStreamGate.java'
    if digest(gate) != '88d939cadf331a743613bd73eec0bfe99ccd71306490ae01ddc687da97c87d80':
        raise ValueError('NormativeStreamGate source differs from v2')
    run = json.loads((pair / 'run.json').read_text(encoding='utf-8'))
    copied, changed = [], []
    for item in run['classpath_files']:
        path = Path(item['path'])
        actual = digest(path)
        if actual != item['sha256']:
            changed.append({'path': str(path), 'recorded_sha256': item['sha256'], 'current_sha256': actual,
                            'note': 'Current build replaced this file after both paired runs; not falsely preserved as v2'})
            continue
        target = destination / 'classpath' / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        copied.append({'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(target)})
    classes = destination / 'reconstructed-classes'
    classes.mkdir()
    java = ROOT / 'src/build/tools/graalvm-community-openjdk-25.0.2+10.1/bin/javac.exe'
    command = [str(java), '-g', '-encoding', 'UTF-8', '-classpath', run['classpath'], '-d', str(classes), str(policy), str(assistant)]
    with (destination / 'javac.log').open('xb') as output:
        result = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    expected = {Path(f['path']).relative_to(ROOT / 'src/build/classes/java/main').as_posix(): f['sha256']
                for f in run['classpath_files'] if Path(f['path']).is_relative_to(ROOT / 'src/build/classes/java/main')}
    rebuilt = []
    for path in sorted(classes.rglob('*.class')):
        relative = path.relative_to(classes).as_posix()
        rebuilt.append({'path': relative, 'actual_sha256': digest(path), 'expected_v2_sha256': expected.get(relative),
                        'byte_identical_to_v2': digest(path) == expected.get(relative)})
    report = {'kind': 'Post-run source reconstruction with byte verification, not original capture',
              'final_read': False, 'live_classpath_written': False, 'command': command,
              'javac_exit_code': result.returncode, 'source_inventory': source_inventory,
              'reconstructed_source_sha256': {'AnswerPolicy.java': digest(policy), 'AssistantModule.java': digest(assistant), 'NormativeStreamGate.java': digest(gate)},
              'source_reconstruction': 'Only copied AnswerPolicy UNVERIFIED_RESPONSE constant removed; two copied AssistantModule references restored to INSUFFICIENT. No live sources modified.',
              'unchanged_classpath_files_copied_and_hashed': copied, 'later_changed_classpath_files': changed,
              'isolated_recompiled_classes': rebuilt,
              'all_isolated_class_bytes_match_recorded_v2': bool(rebuilt) and all(x['byte_identical_to_v2'] for x in rebuilt)}
    write_json(destination / 'reconstruction.json', report)
    shutil.copy2(Path(__file__), destination / 'reconstruct-source.py')
    print(json.dumps({'path': str(destination), 'sources': len(source_inventory), 'copied_classpath': len(copied),
                      'later_changed': len(changed), 'javac_exit_code': result.returncode, 'rebuilt_classes': rebuilt}, ensure_ascii=False))


if __name__ == '__main__':
    main()
