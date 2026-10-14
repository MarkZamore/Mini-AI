"""Developer-only immutable input audit. Outputs go exclusively to src/build/test-results."""
import argparse
import hashlib
import json
from pathlib import Path

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            result.update(block)
    return result.hexdigest()

def inventory(root):
    result = {}
    for folder in ('docs', 'db'):
        for path in sorted((root / folder).rglob('*')):
            if not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            stat = path.stat()
            # Runtime tool packages are not executed by the application. Record their
            # identities and mtimes; hash the originals, canonical data, history/artifacts.
            result[relative] = {'size': stat.st_size, 'mtime_ns': stat.st_mtime_ns,
                                'sha256': None if relative.startswith('db/_runtime/') else digest(path)}
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('snapshot', 'check'))
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    root = args.root.resolve()
    output = root / 'src' / 'build' / 'test-results'
    output.mkdir(parents=True, exist_ok=True)
    baseline = output / 'data-before.json'
    actual = inventory(root)
    if args.mode == 'snapshot':
        if baseline.exists():
            raise SystemExit('Baseline already exists; refusing to replace it.')
        baseline.write_text(json.dumps(actual, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
        print(json.dumps({'snapshot_files':len(actual), 'hashed_files':sum(v['sha256'] is not None for v in actual.values())}))
        return
    expected = json.loads(baseline.read_text(encoding='utf-8'))
    changed = [key for key in sorted(set(expected) | set(actual)) if expected.get(key) != actual.get(key)]
    report = {'unchanged':not changed, 'files':len(actual), 'changes':changed}
    (output / 'data-integrity.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))
    if changed:
        raise SystemExit(1)

if __name__ == '__main__':
    main()
