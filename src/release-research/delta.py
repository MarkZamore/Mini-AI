"""Plan a pinned full-application delta or build it after explicit frozen authority.

Planning never accepts a development configuration. The separate build command
requires an explicit new frozen distribution authority; no command publishes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import zipfile
from pathlib import Path

from portable import load, native, ordinary_file, plain_absolute, safe_relative, sha

BASE_TAG = 'v2026.10.24-local-ai'
BASE_CONFIGURATION = 'mini-ai-20261024-4b-base-e5-foundation-fallback-v1'
BASE_ASSETS_SHA = '549511b24c076eae23289dc373e820e86dc907d32121a10bfd614e305c8636b0'
BASE_INVENTORY_SHA = '6c13c42b56c141288fb5ae6749f21053bf015bb6fb6a71465572afc9c29e490f'
REPOSITORY = 'MarkZamore/Mini-AI'


def inventory(record):
    rows = record.get('files')
    if not isinstance(rows, list) or not rows or record.get('file_count') != len(rows):
        raise ValueError('A complete counted full-application inventory is required')
    result, folded = {}, set()
    for row in rows:
        if set(row) != {'path', 'bytes', 'sha256'}:
            raise ValueError('Unexpected inventory row fields')
        path = safe_relative(row['path'])
        if path.casefold() in folded:
            raise ValueError('Duplicate or Windows case-colliding inventory path')
        if type(row['bytes']) is not int or row['bytes'] < 0 or not re.fullmatch('[0-9a-f]{64}', row['sha256']):
            raise ValueError('Invalid inventory size/SHA')
        result[path] = row
        folded.add(path.casefold())
    if record.get('unpacked_bytes') != sum(r['bytes'] for r in rows):
        raise ValueError('Inventory byte total differs')
    required = {'Mini-AI.exe', 'model/worker.json', 'model/inference-worker.exe'}
    if not required.issubset(result) or not any(p.startswith('db/') for p in result):
        raise ValueError('Full app, inference package and corpus inventory required')
    for path in result:
        parts=path.casefold().split('/')
        if any('/'.join(parts[:index]) in folded for index in range(1,len(parts))):
            raise ValueError('Inventory file/directory prefix collision')
    return result


def inspect_target(root, rows):
    """Exact metadata inventory without hashing unchanged multi-GB files."""
    actual=set()
    pending=[root]
    while pending:
        current=pending.pop()
        info=native(current).lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info,'st_file_attributes',0)&0x400:
            raise ValueError('Linked/reparse target directory is unsupported')
        with os.scandir(native(current)) as directory:
            for entry in directory:
                path=current/entry.name
                info=entry.stat(follow_symlinks=False)
                if stat.S_ISLNK(info.st_mode) or getattr(info,'st_file_attributes',0)&0x400:
                    raise ValueError('Linked/reparse target entry is unsupported')
                if stat.S_ISDIR(info.st_mode):
                    pending.append(path)
                elif stat.S_ISREG(info.st_mode):
                    relative=path.relative_to(root).as_posix()
                    if relative not in rows or info.st_size!=rows[relative]['bytes']:
                        raise ValueError('Actual target path/size differs from full inventory')
                    actual.add(relative)
                else:
                    raise ValueError('Unsupported target entry type')
    if actual!=set(rows):
        raise ValueError('Target is missing files from the full inventory')


def independent_output(path):
    for entry in (path,*path.parents):
        if native(entry).exists():
            info=native(entry).lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info,'st_file_attributes',0)&0x400:
                raise ValueError('Output cannot cross links/reparse points')


def pinned_assets(record):
    if record.get('tag') != BASE_TAG or record.get('configuration_id') != BASE_CONFIGURATION:
        raise ValueError('Only the exact original published foundation base is supported by this scaffold')
    rows = record['assets']
    expected = {'Mini-AI-setup.zip', 'SHA256SUMS.txt'} | {f'Mini-AI.payload.part{i:03d}' for i in range(1, 8)}
    if {row['name'] for row in rows} != expected or len(rows) != len(expected):
        raise ValueError('Unexpected foundation publication asset set')
    result = []
    for row in rows:
        name = safe_relative(row['name'])
        if '/' in name or type(row['bytes']) is not int or row['bytes'] <= 0 or not re.fullmatch('[0-9a-f]{64}', row['sha256']):
            raise ValueError('Invalid publication asset metadata')
        url = f'https://github.com/{REPOSITORY}/releases/download/{BASE_TAG}/{name}'
        if 'url' in row and row['url'] != url:
            raise ValueError('Unpinned base asset URL')
        result.append({'name':name, 'bytes':row['bytes'], 'sha256':row['sha256'], 'url':url})
    return result


def published_setup(path, assets_record, base_record, base):
    asset=next(row for row in assets_record['assets'] if row['name']=='Mini-AI-setup.zip')
    ordinary_file(path,path.parent)
    if native(path).stat().st_size!=asset['bytes'] or sha(path)!=asset['sha256']:
        raise ValueError('Published base setup bytes/SHA differ')
    with zipfile.ZipFile(native(path)) as archive:
        names=archive.namelist()
        if len(names)!=len({name.casefold() for name in names}):
            raise ValueError('Duplicate/case-colliding base setup entries')
        def read(name):
            info=archive.getinfo('Mini-AI/'+safe_relative(name))
            if info.file_size>4*1024**2:
                raise ValueError('Unexpectedly large base setup metadata')
            return archive.read(info)
        metadata=json.loads(read('portable-release.json'))
        raw_inventory=read('portable-files.json')
        if hashlib.sha256(raw_inventory).hexdigest()!=metadata['inventory_sha256']:
            raise ValueError('Public base inventory metadata SHA mismatch')
        public_inventory=json.loads(raw_inventory)
        if (metadata['tag']!=BASE_TAG or metadata['repository']!=REPOSITORY
                or metadata['configuration_id']!=BASE_CONFIGURATION
                or public_inventory['configuration_id']!=BASE_CONFIGURATION):
            raise ValueError('Public base configuration differs')
        public_rows=inventory({'files':public_inventory['files'],'file_count':metadata['file_count'],
                               'unpacked_bytes':metadata['unpacked_bytes']})
        if public_rows!=base:
            raise ValueError('Public base inventory differs from exact frozen base')
        for key in ('app_sha256','model_manifest_sha256','corpus_sha256'):
            if metadata[key]!=base_record[key]:
                raise ValueError('Public base app/model/corpus fingerprint differs')
        profiles=metadata['profiles']
        if profiles['default']!='cpu' or set(profiles)!={'default','cpu','cuda','notice'}:
            raise ValueError('Unexpected base profile contract')
        records,decoded={},{}
        for kind in ('cpu','cuda'):
            record=profiles[kind]
            if record['path']!=f'profiles/worker.{kind}.json':
                raise ValueError('Unexpected public profile path')
            raw=read(record['path'])
            if len(raw)!=record['bytes'] or hashlib.sha256(raw).hexdigest()!=record['sha256']:
                raise ValueError('Public profile bytes/SHA mismatch')
            decoded[kind]=json.loads(raw)
            records[kind]=record
        if records['cuda']['sha256']!=base['model/worker.json']['sha256']:
            raise ValueError('Canonical base inventory must match the public CUDA profile')
        if decoded['cpu']['backend']!={'kind':'cpu','device':0,'gpuMemoryBudgetBytes':0,'reserveFreeGpuMemoryBytes':0}:
            raise ValueError('Base CPU profile differs from the explicit CPU-only backend')
        first=dict(decoded['cpu']);second=dict(decoded['cuda'])
        first.pop('backend');second.pop('backend')
        if first!=second:
            raise ValueError('Base profiles change files/weights outside the backend')
        return {'setup_sha256':asset['sha256'],'public_inventory_sha256':metadata['inventory_sha256'],
                'default_profile':'cpu','accepted_installed_worker_profiles':records,
                'notice':'The default installed CPU worker.json differs from canonical CUDA inventory. A future updater must accept exactly these known base profiles, preserve the selected backend, and supply independently verified new CPU/CUDA profiles.'}


def plan(args):
    base_path, assets_path, target_path = [plain_absolute(p) for p in (args.base_inventory, args.base_assets, args.target_inventory)]
    for path in (base_path, assets_path, target_path):
        ordinary_file(path, path.parent)
    if sha(base_path) != BASE_INVENTORY_SHA or sha(assets_path) != BASE_ASSETS_SHA:
        raise ValueError('Foundation base inventory/publication fingerprint differs')
    base_record, target_record = load(base_path), load(target_path)
    base, target = inventory(base_record), inventory(target_record)
    assets_record=load(assets_path)
    public_base=published_setup(plain_absolute(args.base_setup),assets_record,base_record,base)
    base_root, target_root = [plain_absolute(Path(r['root'])) for r in (base_record, target_record)]
    if len(base) != 6811 or not native(base_root).is_dir() or not native(target_root).is_dir():
        raise ValueError('Exact foundation base and an existing full target stage are required')
    if base_root == target_root:
        raise ValueError('Use a separately staged target; never mutate the published base')
    base_folded={path.casefold():path for path in base}
    target_folded={path.casefold():path for path in target}
    if any(base_folded[key]!=target_folded[key] for key in base_folded.keys()&target_folded.keys()):
        raise ValueError('Case-only Windows renames require a separate explicit migration design')
    union=set(base_folded)|set(target_folded)
    for path in union:
        parts=path.split('/')
        if any('/'.join(parts[:index]) in union for index in range(1,len(parts))):
            raise ValueError('File/directory transitions require a separate explicit migration design')
    if args.target_tag == BASE_TAG or not re.fullmatch(r'v[0-9A-Za-z][0-9A-Za-z._-]{0,95}', args.target_tag):
        raise ValueError('A distinct safe prospective tag is required (no Git tag is created)')
    out = plain_absolute(args.out)
    independent_output(out)
    if native(out).exists():
        raise ValueError('Preserve previous plans; choose a new output file')
    for root in (base_root, target_root):
        if out == root or root in out.parents:
            raise ValueError('Do not write a plan inside either runtime')
    inspect_target(target_root,target)
    changed = [path for path in sorted(target) if path not in base or (base[path]['bytes'],base[path]['sha256']) != (target[path]['bytes'],target[path]['sha256'])]
    replacements = []
    for path in changed:
        source = ordinary_file(target_root / path, target_root)
        before = source.stat()
        digest = sha(source)
        after = source.stat()
        if (before.st_ino,before.st_size,before.st_mtime_ns) != (after.st_ino,after.st_size,after.st_mtime_ns):
            raise ValueError('Changed target file was modified during hashing')
        row = target[path]
        if after.st_size != row['bytes'] or digest != row['sha256']:
            raise ValueError(f'Changed target file does not match inventory: {path}')
        replacements.append({'path':path,'bytes':row['bytes'],'sha256':digest,
                             'expected_base':base.get(path)})
    result = {
        'schema_version':1,'state':'scaffold-only-not-release-ready',
        'prospective_tag':args.target_tag,'repository':REPOSITORY,
        'base':{'tag':BASE_TAG,'configuration_id':BASE_CONFIGURATION,
                'inventory_sha256':sha(base_path),'publication_assets_sha256':sha(assets_path),
                'files':len(base),'unpacked_bytes':base_record['unpacked_bytes'],
                'pinned_assets':pinned_assets(assets_record), 'published_setup':public_base},
        'target':{'inventory_sha256':sha(target_path),'files':len(target),
                  'unpacked_bytes':target_record['unpacked_bytes'],
                  'actual_full_path_and_size_inventory_checked':True,
                  'source_record_state':target_record.get('state'),
                  'notice':target_record.get('quality_notice','No acceptance evidence is implied by this inventory.')},
        'delta':{'replacement_files':replacements,'replacement_logical_bytes':sum(row['bytes'] for row in replacements),
                 'unchanged_files':sum(1 for path in target if path in base and base[path]==target[path]),
                 'obsolete_owned_paths':[base[path] for path in sorted(base.keys()-target.keys())]},
        'not_implemented_or_verified':[
            'Accepted quality decision, new full-seven-module application build, frozen target and source/tag consistency gate',
            'Streaming delta ZIP writer and exact payload inventory/hash verifier',
            'Installer: base integrity verification, safe local ownership, unknown-file preservation and pinned base downloads',
            'Installer: closed-app handling, atomic replacements, durable rollback journal and fault recovery',
            'CPU/CUDA target profile parity and hashes',
            'Negative transport fixtures and real target installation/GUI/source/cancel checks',
            'Publication assets, immutable new release and public endpoint verification'],
        'capabilities':{'plan_only':True,'build':False,'install':False,'promote':False,'upload':False,'quality_acceptance':False},
        'scope':'Reads complete inventories and hashes changed target files only. No model inference, runtime modification, payload copy, archive creation or GitHub mutation.'}
    out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('x',encoding='utf-8') as stream:
        json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps({'plan':str(out),'sha256':sha(out),'state':result['state'],
                      'replacement_files':len(replacements),'replacement_logical_bytes':result['delta']['replacement_logical_bytes']}))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('plan',help='Inspect the delta without creating archives')
    for name in ('base-inventory','base-assets','base-setup','target-inventory','out'):
        p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--target-tag',required=True)
    b=sub.add_parser('build',help='Create installer/update assets only for a new explicitly approved frozen runtime')
    for name in ('base-inventory','base-assets','base-setup','target-inventory','out','authority'):
        b.add_argument('--'+name,type=Path,required=True)
    b.add_argument('--target-tag',required=True)
    b.add_argument('--configuration-id',required=True)
    args=parser.parse_args()
    if args.command=='plan':plan(args)
    else:
        from delta_package import build
        build(args)


if __name__=='__main__':
    main()
