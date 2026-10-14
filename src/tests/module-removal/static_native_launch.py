"""Observer for a static native composition; no app-local runtime is copied."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'portability'))
from static_pe import assert_static_imports, assert_static_modules, process_modules
from portable_transfer import extended

def main():
    exe, command, timeout, expected, cwd = sys.argv[1:]
    exe = Path(exe).absolute()
    imports = assert_static_imports(exe)
    env = dict(os.environ)
    for name in ('JAVA_HOME', 'PYTHONHOME', 'PYTHONPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(name, None)
    env['PATH'] = os.path.join(env['SYSTEMROOT'], 'System32') + os.pathsep + env['SYSTEMROOT']
    child = subprocess.Popen([extended(exe), '--' + command], executable=extended(exe), cwd=cwd, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='strict')
    try:
        modules = set()
        if command == 'ui-smoke':
            for attempt in range(25):
                modules.update(process_modules(child._handle))
                if child.poll() is not None: break
                time.sleep(.04)
        stdout, stderr = child.communicate(timeout=float(timeout))
        if child.returncode != int(expected) or stderr:
            raise AssertionError(f'Static {command}: exit={child.returncode}; stderr={stderr}; stdout={stdout}')
        payload = json.loads(stdout)
        if command == 'ui-smoke':
            assert_static_modules(exe, modules)
            payload['observerLoadedModules'] = sorted(modules)
            payload['observerPeImports'] = imports
        sys.stdout.reconfigure(encoding='utf-8')
        print(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
    finally:
        if child.poll() is None: child.kill(); child.wait()

if __name__ == '__main__': main()
