"""Shared, local-only document database primitives."""
from __future__ import annotations
import hashlib, json, os, sys, tempfile
from datetime import datetime, timezone
from pathlib import Path

DB_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(DB_ROOT / '_runtime' / 'python'))
PYTHON = Path(r'C:\Users\Oscar\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe')
POPLER = Path(r'C:\Users\Oscar\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\poppler\Library\bin')
CREATE_NO_WINDOW = 0x08000000 if os.name == 'nt' else 0

def utc():
    return datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')

def native(path):
    value = os.path.abspath(str(path))
    if os.name == 'nt' and not value.startswith('\\\\?\\'):
        return '\\\\?\\UNC\\' + value[2:] if value.startswith('\\\\') else '\\\\?\\' + value
    return value

def sha256(path):
    digest = hashlib.sha256()
    with open(native(path), 'rb') as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()

def read_json(path):
    with open(native(path), encoding='utf-8') as source:
        return json.load(source)

def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, tmp = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=native(path.parent))
    try:
        with os.fdopen(handle, 'w', encoding='utf-8', newline='\n') as target:
            json.dump(value, target, ensure_ascii=False, allow_nan=False, separators=(',', ':'))
            target.write('\n'); target.flush(); os.fsync(target.fileno())
        os.replace(tmp, native(path))
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

def relative(path, root=DB_ROOT):
    return os.path.relpath(str(path), str(root)).replace('\\', '/')

def stored_path(root, value, allow_source=False):
    path = (Path(root) / value).resolve()
    allowed = Path(root).parent if allow_source else Path(root)
    if os.path.commonpath([str(path), str(allowed.resolve())]) != str(allowed.resolve()):
        raise ValueError('Path escapes allowed database/source roots')
    return path

def artifact(root, path, derived_from, kind):
    digest = sha256(path)
    return {'id': 'sha256:' + digest, 'path': relative(path, root), 'format': Path(path).suffix.lstrip('.').lower(),
            'sha256': digest, 'size': os.stat(native(path)).st_size, 'derived_from': derived_from, 'kind': kind}

def validate_document(document, root=DB_ROOT):
    from jsonschema import Draft202012Validator
    schema = read_json(Path(root) / 'schema' / 'document.schema.json')
    Draft202012Validator(schema).validate(document)
    ids = {b['id'] for b in document['blocks']}
    if len(ids) != len(document['blocks']): raise ValueError('Duplicate block IDs')
    artifacts = {'original'} | {a['id'] for a in document['artifacts']}
    for block in document['blocks']:
        if block['parent_id'] is not None and block['parent_id'] not in ids:
            raise ValueError('Unknown parent block')
        refs = list(block['source_refs'])
        if block['kind'] == 'table':
            for cell in block['payload']['cells']: refs.extend(cell['source_refs'])
        for ref in refs:
            if ref['artifact_id'] not in artifacts: raise ValueError('Unknown source artifact')
            if ref['source_sha256'] != document['source_version_sha256']: raise ValueError('Wrong source version')
    for item in document['assets']:
        if item['artifact_id'] not in artifacts: raise ValueError('Unknown asset artifact')
