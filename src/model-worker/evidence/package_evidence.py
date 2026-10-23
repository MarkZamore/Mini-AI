"""Assemble an immutable, fully hashed standalone worker/evidence package.

This changes no canonical documents or SQLite database. Existing output paths
are refused so that baseline packages and checkpoints cannot be overwritten.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--generation-package', required=True, type=Path)
    parser.add_argument('--worker', required=True, type=Path)
    parser.add_argument('--embedder', required=True, type=Path)
    parser.add_argument('--index', required=True, type=Path)
    parser.add_argument('--reranker', type=Path)
    parser.add_argument('--generator', type=Path)
    parser.add_argument('--generator-name')
    parser.add_argument('--answer-policy', choices=['none','bge-relevance-v1'], default='none')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--min-score', type=float)
    args = parser.parse_args()
    source = args.generation_package.resolve(strict=True)
    output = args.output.resolve()
    if output.exists():
        raise ValueError('Output already exists; choose a new versioned package directory')
    manifest = json.loads((source/'worker.json').read_text(encoding='utf-8'))
    if manifest['schemaVersion'] != 2:
        raise ValueError('A verified v2 generation package is required')
    index_file = args.index.resolve(strict=True)
    index = json.loads(index_file.read_text(encoding='utf-8'))
    encoder = args.embedder.resolve(strict=True)/'encoder.onnx'
    if sha(encoder) != index['embedderSha256']:
        raise ValueError('Index and embedding model fingerprints differ')
    output.mkdir(parents=True)

    def asset(original, relative, mutable=False):
        original = original.resolve(strict=True)
        destination = output/relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            destination.unlink()
        if mutable:
            shutil.copy2(original, destination)
        else:
            try:
                os.link(original, destination)
            except OSError:
                shutil.copy2(original, destination)
        return sha(destination)

    for record in manifest['files']:
        relative = record['path']
        if relative == manifest['executable']:
            continue
        if args.generator and relative == manifest['weights']['path']:
            continue
        original = (source/relative).resolve(strict=True)
        original.relative_to(source)
        if sha(original) != record['sha256']:
            raise ValueError(f'Generation asset failed verification: {relative}')
        destination = relative.lower() if relative.lower().startswith('runtime/') else relative
        asset(original, destination)
    for dependency in manifest['dependencies']:
        dependency['path'] = dependency['path'].lower()
    asset(args.worker, manifest['executable'], mutable=True)
    if args.generator:
        name=args.generator_name or args.generator.name
        if not name.endswith('.gguf') or any(c in name for c in '/\\:'):
            raise ValueError('Generator name must be a GGUF filename')
        path='weights/'+name
        manifest['weights']={'path':path,'sha256':asset(args.generator,path)}
    encoder_sha = asset(encoder, 'evidence/encoder.onnx')
    tokenizer = args.embedder/'tokenizer'
    for original in tokenizer.rglob('*'):
        if original.is_file():
            asset(original, Path('evidence/tokenizers/embedder')/original.relative_to(tokenizer))
    for name in ('vectors','fragments'):
        original = (index_file.parent/index[name]).resolve(strict=True)
        original.relative_to(index_file.parent)
        if sha(original) != index[name+'Sha256']:
            raise ValueError(f'Index asset failed verification: {name}')
        relative = 'evidence/'+original.name
        asset(original, relative)
        index[name] = relative
    if args.min_score is not None:
        if not -1 <= args.min_score <= 1:
            raise ValueError('Cosine retrieval cutoff must be in [-1,1]')
        index['minScore'] = args.min_score
    if args.answer_policy!='none':
        if not args.reranker:
            raise ValueError('The selected relevance gate requires a reranker')
        index['answerPolicy']={'kind':'bge-relevance-v1','maxCandidates':3,'adequateMinLogit':0.,'insufficientMaxLogit':-2.5}
    (output/'evidence/index.json').write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
    manifest['evidence'] = {
        'embedder': {'path':'evidence/encoder.onnx','sha256':encoder_sha},
        'tokenizerDirectory':'evidence/tokenizers',
        'index': {'path':'evidence/index.json','sha256':sha(output/'evidence/index.json')}
    }
    if args.reranker:
        rank = args.reranker.resolve(strict=True)
        rank_sha = ''
        for original in rank.rglob('*'):
            if original.is_file() and 'tokenizer' not in original.relative_to(rank).parts:
                relative = Path('evidence/reranker')/original.relative_to(rank)
                current_sha = asset(original, relative)
                if original.name == 'reranker.onnx':
                    rank_sha = current_sha
        if not rank_sha:
            raise ValueError('Reranker graph is missing')
        for original in (rank/'tokenizer').rglob('*'):
            if original.is_file():
                asset(original, Path('evidence/tokenizers/reranker')/original.relative_to(rank/'tokenizer'))
        manifest['evidence']['reranker'] = {'path':'evidence/reranker/reranker.onnx','sha256':rank_sha}
    legal=Path(__file__).parent/'licenses'
    for name in ('E5-MIT.txt','MODEL-NOTICES.md'):
        if not (legal/name).is_file():raise ValueError('Model licence asset is missing: '+name)
        asset(legal/name,Path('licenses')/name)
    card=args.embedder.parent.parent/'base/README.md'
    if card.is_file():asset(card,'licenses/E5-MODEL-CARD.md')
    if args.reranker:
        asset(legal/'BGE-Apache-2.0.txt','licenses/BGE-Apache-2.0.txt')
        card=args.reranker.parent/'base/README.md'
        if card.is_file():asset(card,'licenses/BGE-MODEL-CARD.md')
    # Every graph sidecar, tokenizer, licence and index dependency is covered.
    manifest['files'] = [{'path':p.relative_to(output).as_posix(),'sha256':sha(p)}
                         for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'worker.json']
    (output/'worker.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'output':str(output),'files':len(manifest['files']),
                      'rows':index['rows'],'manifestSha256':sha(output/'worker.json')},ensure_ascii=False))


if __name__ == '__main__':
    main()
