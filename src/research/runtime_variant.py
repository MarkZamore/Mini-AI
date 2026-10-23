"""Create a new research runtime without changing an earlier model/corpus.

All application modules remain present. Variants change only the experimental
evidence model profile; this is not a module-removal test.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--source',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--mode',required=True,choices=['lexical','hybrid','gate'])
    args=parser.parse_args();source=args.source.resolve(strict=True);output=args.output.resolve()
    if output.exists():raise ValueError('Use a new versioned runtime directory')
    output.mkdir(parents=True)
    for directory in ('native','db','model'):
        for original in (source/directory).rglob('*'):
            if not original.is_file() or original.name=='worker.json':continue
            destination=output/original.relative_to(source);destination.parent.mkdir(parents=True,exist_ok=True)
            try:os.link(original,destination)
            except OSError:shutil.copy2(original,destination)
    manifest=json.loads((source/'model/worker.json').read_text(encoding='utf-8'))
    if args.mode=='lexical':manifest.pop('evidence',None)
    elif args.mode=='hybrid':manifest['evidence'].pop('reranker',None)
    else:
        if 'reranker' not in manifest['evidence']:raise ValueError('Gate requires a verified BGE model')
        index=output/'model'/manifest['evidence']['index']['path']
        value=json.loads(index.read_text(encoding='utf-8'))
        value['answerPolicy']={'kind':'bge-relevance-v1','maxCandidates':3,'adequateMinLogit':0.,'insufficientMaxLogit':-2.5}
        # Break the hardlink before writing experiment metadata.
        index.unlink();index.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
        digest=sha(index);manifest['evidence']['index']['sha256']=digest
        next(r for r in manifest['files'] if r['path']==manifest['evidence']['index']['path'])['sha256']=digest
    (output/'model/worker.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    metadata={'schemaVersion':1,'mode':args.mode,'sourceManifestSha256':sha(source/'model/worker.json'),
              'manifestSha256':sha(output/'model/worker.json'),'composition':'All seven application modules; evidence profile control only'}
    (output/'variant.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
    print(json.dumps(metadata))


if __name__=='__main__':main()
