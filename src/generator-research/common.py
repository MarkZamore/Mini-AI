"""Local generator experiments; frozen final questions are never opened here."""
from __future__ import annotations
import contextlib, hashlib, json, os, pathlib, random, time, uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORK = ROOT / 'src/build/research-20261024'
OUT = WORK / 'generator'
DATA = WORK / 'data'
BASES = ROOT / 'src/build/model-preparation/base'
SEED = 20261024

def digest(path):
    with pathlib.Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def read_jsonl(path):
    path = pathlib.Path(path).resolve()
    if 'sealed-final' in path.parts:
        raise ValueError('Final questions and labels must not be read by generator training/selection')
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]

def write_json(path, value):
    path = pathlib.Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f'.{os.getpid()}.partial')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    os.replace(temporary, path)

def write_jsonl(path, rows):
    path = pathlib.Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')

def log(path, row):
    path = pathlib.Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a', encoding='utf-8') as stream:
        stream.write(json.dumps(row, ensure_ascii=False) + '\n'); stream.flush()

@contextlib.contextmanager
def gpu_lease(task, wait_seconds=3600):
    """One GPU owner across agents. Never replace another live or stale lease blindly."""
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / 'gpu.lock'; nonce = uuid.uuid4().hex
    payload = {'pid': os.getpid(), 'owner': 'model_options', 'agent': 'model_options', 'task': task, 'nonce': nonce}
    began = time.monotonic(); announced = False
    while True:
        try:
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            with os.fdopen(fd, 'w', encoding='utf-8') as stream: json.dump(payload, stream)
            break
        except FileExistsError:
            if not announced:
                print('GPU lease occupied; generator waits without initializing CUDA', flush=True); announced = True
            if time.monotonic() - began > wait_seconds:
                raise TimeoutError('GPU lease wait exceeded; current lease was preserved')
            time.sleep(5)
    try:
        yield
    finally:
        if path.exists():
            try:
                if json.loads(path.read_text(encoding='utf-8')).get('nonce') == nonce: path.unlink()
            except (OSError, ValueError): pass

def offline():
    for name in ('HF_HUB_OFFLINE', 'TRANSFORMERS_OFFLINE', 'HF_HUB_DISABLE_TELEMETRY'):
        os.environ[name] = '1'
    os.environ['TOKENIZERS_PARALLELISM'] = 'false'
    os.environ['HF_HOME'] = str(WORK / 'cache/huggingface')
    os.environ['PYTHONUTF8'] = '1'

def foundation_lock(model_name):
    base = BASES / model_name
    proof_path = ROOT / 'src/build/model-preparation/evidence' / ('Qwen--' + model_name + '--files-verified.json')
    proof = json.loads(proof_path.read_text(encoding='utf-8'))
    files = []
    for item in proof['files']:
        name = item['path']
        if name.endswith('.safetensors') or name in ('config.json', 'model.safetensors.index.json', 'tokenizer.json', 'tokenizer_config.json', 'chat_template.jinja'):
            actual = digest(base / name)
            if actual != item['sha256']: raise ValueError('Foundation checksum mismatch: ' + name)
            files.append({'path': name, 'sha256': actual, 'bytes': (base / name).stat().st_size})
    return {'repo': proof['repo'], 'revision': proof['revision'], 'files': files, 'old_adapters_used': False}
