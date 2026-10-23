"""CPU-only native evidence cancellation/lifetime regression through real JSONL.

Uses the supplied prepared worker directly. It requests no text generation and
does not inspect final evaluation data. Actual frames/exit codes are preserved;
a clean exit is not an instrumented memory-safety proof.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import queue
import subprocess
import threading
import time

PROTOCOL = 'enterpriseai.evidence.v1'
MAX_FRAME = 8 * 1024 * 1024


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


class Worker:
    def __init__(self, directory, transcript, timeout):
        self.transcript = transcript
        self.timeout = timeout
        self.started = time.perf_counter()
        self.frames = queue.Queue()
        self.stderr = bytearray()
        self.process = subprocess.Popen(
            [str(directory / 'inference-worker.exe'), '--stdio'], cwd=directory,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.errors = threading.Thread(target=self._stderr, daemon=True)
        self.reader.start()
        self.errors.start()
        self.record('process', {'pid': self.process.pid})

    def record(self, direction, value):
        self.transcript.append({'seconds': time.perf_counter() - self.started,
                                'direction': direction, 'value': value})

    def _read(self):
        try:
            while True:
                line = self.process.stdout.readline(MAX_FRAME + 2)
                if not line:
                    self.frames.put(('eof', None))
                    return
                if len(line.rstrip(b'\r\n')) > MAX_FRAME or not line.endswith(b'\n'):
                    raise ValueError('Oversized or unterminated native frame')
                self.frames.put(('frame', json.loads(line.decode('utf-8', errors='strict'))))
        except BaseException as error:
            self.frames.put(('reader_error', repr(error)))

    def _stderr(self):
        while block := self.process.stderr.read(4096):
            self.stderr.extend(block)
            del self.stderr[:-65536]

    def send(self, frame):
        self.record('send', frame)
        self.process.stdin.write((json.dumps(frame, ensure_ascii=False) + '\n').encode('utf-8'))
        self.process.stdin.flush()

    def malformed(self):
        text = '{"type":"retrieve", malformed}\n'
        self.record('send_malformed', text)
        self.process.stdin.write(text.encode('ascii'))
        self.process.stdin.flush()

    def receive(self):
        kind, value = self.frames.get(timeout=self.timeout)
        self.record(kind, value)
        if kind != 'frame':
            raise RuntimeError(f'Unexpected native {kind}: {value}')
        if not isinstance(value, dict):
            raise ValueError('Native frame must be an object')
        return value

    def hello(self):
        self.send({'type': 'hello', 'protocol': PROTOCOL})
        if self.receive() != {'type': 'ready', 'protocol': PROTOCOL}:
            raise AssertionError('Worker did not acknowledge evidence protocol')

    def retrieve(self, request_id, query):
        self.send({'type': 'retrieve', 'requestId': request_id, 'baseId': 'local',
                   'query': query, 'limit': 3, 'documentIds': [], 'sections': []})

    def assert_retrieved(self, request_id):
        frame = self.receive()
        if frame.get('type') != 'result' or frame.get('requestId') != request_id:
            raise AssertionError(f'Retrieve did not finish: {frame}')
        hits = frame.get('result', {}).get('hits')
        if not isinstance(hits, list) or len(hits) > 3:
            raise AssertionError('Invalid retrieve inventory')

    def long_rerank(self, request_id, query):
        # 128 individual CPU cross-encoder passes, bounded by the wire contract.
        # BUSY confirmation proves the malformed/cancel frame arrives while a
        # native job owns the Evidence object, rather than after completion.
        passage = ('Условия, сроки, ограничения и сохранение конфиденциальных сведений. '
                   'Құжаттағы шарттар, талаптар және ерекшеліктер. ') * 40
        self.send({'type': 'rerank', 'requestId': request_id, 'query': query,
                   'candidates': [{'fragmentId': f'lifecycle-{i}', 'documentId': 'lifecycle',
                                   'text': passage} for i in range(128)]})
        self.retrieve(request_id + '-busy-check', query)
        busy = self.receive()
        if busy.get('type') != 'error' or busy.get('code') != 'BUSY' or busy.get('requestId') != request_id + '-busy-check':
            raise AssertionError(f'An active native job was not confirmed: {busy}')

    def finish(self, expected, close_input=False):
        if close_input and not self.process.stdin.closed:
            self.process.stdin.close()
        code = self.process.wait(timeout=self.timeout)
        self.reader.join(timeout=2)
        self.errors.join(timeout=2)
        while not self.frames.empty():
            kind, value = self.frames.get_nowait()
            self.record(kind, value)
        self.record('exit', {'returncode': code, 'returncode_u32': code & 0xffffffff})
        if code != expected:
            raise AssertionError(f'Expected exit {expected}, observed {code} / 0x{code & 0xffffffff:08x}')
        return code

    def close(self):
        forced = self.process.poll() is None
        if forced:
            self.process.kill()
            self.process.wait(timeout=10)
        self.reader.join(timeout=2)
        self.errors.join(timeout=2)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            if not stream.closed:
                stream.close()
        return {'returncode': self.process.returncode,
                'returncode_u32': self.process.returncode & 0xffffffff,
                'forced_cleanup': forced,
                'stderr_tail': self.stderr.decode('utf-8', errors='replace')}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--query', default='Условия сохранения сведений в документе')
    args = parser.parse_args()
    if args.report.exists():
        raise FileExistsError('Preserve earlier lifecycle probe report')
    directory = args.package.resolve(strict=True)
    manifest = json.loads((directory / 'worker.json').read_text(encoding='utf-8-sig'))
    if 'reranker' not in manifest.get('evidence', {}):
        raise ValueError('An actual native reranker is required for this regression')
    report = {
        'scope': 'Real native CPU evidence JSONL lifecycle regression; not an instrumented memory-safety proof',
        'package': str(directory), 'manifest_sha256': sha(directory / 'worker.json'),
        'worker_sha256': sha(directory / 'inference-worker.exe'), 'probe_sha256': sha(Path(__file__)),
        'generation_requested': False, 'gpu_generation_requested': False,
        'human_verified': False, 'final_read': False, 'scenarios': [], 'passed': False,
    }
    try:
        for scenario in ('retrieve-cancel-retrieve', 'active-job-malformed-json'):
            item = {'scenario': scenario, 'frames': [], 'passed': False}
            report['scenarios'].append(item)
            worker = Worker(directory, item['frames'], args.timeout)
            try:
                worker.hello()
                if scenario == 'retrieve-cancel-retrieve':
                    worker.retrieve('initial-retrieve', args.query)
                    worker.assert_retrieved('initial-retrieve')
                    worker.long_rerank('cancel-long-rank', args.query)
                    worker.send({'type': 'cancel', 'requestId': 'cancel-long-rank'})
                    cancelled = worker.receive()
                    if cancelled.get('type') != 'error' or cancelled.get('requestId') != 'cancel-long-rank' or cancelled.get('code') != 'CANCELLED':
                        raise AssertionError(f'Expected actual CANCELLED terminal frame: {cancelled}')
                    worker.retrieve('after-cancel-retrieve', args.query)
                    worker.assert_retrieved('after-cancel-retrieve')
                    worker.finish(0, close_input=True)
                else:
                    worker.long_rerank('malformed-long-rank', args.query)
                    worker.malformed()
                    worker.finish(2)
                    received = [e['value'] for e in item['frames'] if e['direction'] == 'frame']
                    if not any(f.get('type') == 'error' and f.get('code') == 'EVIDENCE_STARTUP' for f in received):
                        raise AssertionError('Missing controlled protocol-failure frame')
                item['passed'] = True
            except BaseException as error:
                item['error'] = repr(error)
                raise
            finally:
                item.update(worker.close())
        report['passed'] = all(item['passed'] for item in report['scenarios'])
    except BaseException as error:
        report['error'] = repr(error)
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open('x', encoding='utf-8') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
    print(json.dumps({'passed': report['passed'], 'report': str(args.report),
                      'scenarios': [{k: v for k, v in item.items() if k not in ('frames', 'stderr_tail')} for item in report['scenarios']]}, ensure_ascii=False))
    if not report['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
