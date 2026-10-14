"""Regression for Unicode Windows native-launcher arguments and all Python search fixtures.

Run with Python -B. Creates reports only in src/build/test-results.
Uses Windows subprocess/CreateProcessW so Russian/Kazakh arguments cannot be
silently replaced by the test runner's active legacy code page.
"""
import argparse, hashlib, json, math, subprocess, sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
parser=argparse.ArgumentParser()
parser.add_argument('--exe',type=Path,required=True)
parser.add_argument('--project-root',type=Path,default=Path(__file__).resolve().parents[3])
parser.add_argument('--report-name',default='native-search-parity.json')
args=parser.parse_args()
project=args.project_root.resolve()
cases=json.loads((project/'src/build/test-results/search-parity/search.json').read_text(encoding='utf-8'))
exe=args.exe.resolve()
passed=[]
for number,case in enumerate(cases):
    command=[str(exe),'--search',case['query'],'--limit',str(case['limit'])]
    for document in case['document_ids']:command+=['--document-id',document]
    for section in case['sections']:command+=['--section',section]
    completed=subprocess.run(command,capture_output=True,cwd=project/'src/build',timeout=30)
    if completed.returncode:
        raise AssertionError(f'Case {number}: exit={completed.returncode} stdout={completed.stdout!r} stderr={completed.stderr!r}')
    actual=json.loads(completed.stdout.decode('utf-8'))
    expected=case['expected']
    if not isinstance(actual,list) or len(actual)!=len(expected):
        raise AssertionError(f'Case {number} query={case["query"]!r}: expected {len(expected)} results, received {actual}')
    for position,(received,reference) in enumerate(zip(actual,expected)):
        received=dict(received);reference=dict(reference)
        score=received.pop('score');baseline=reference.pop('score')
        if not math.isclose(score,baseline,rel_tol=0,abs_tol=1e-12):
            raise AssertionError(f'Case {number} position {position}: score {score} != {baseline}')
        if received!=reference:
            changed=[key for key in received.keys()|reference.keys() if received.get(key)!=reference.get(key)]
            raise AssertionError(f'Case {number} position {position}: changed fields {changed}')
    passed.append({'case':number,'query':case['query'],'results':len(actual),'pass':True})
report={'exe':str(exe),'exe_sha256':hashlib.file_digest(exe.open('rb'),'sha256').hexdigest(),'cases':len(passed),'unicode_arguments':True,'cwd_differs_from_executable':True,'full_fragment_and_source_parity':True,'results':passed}
if Path(args.report_name).name != args.report_name or not args.report_name.endswith('.json'):
    raise ValueError('Report name must be a JSON basename under test-results')
target=project/'src/build/test-results'/args.report_name
target.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'PASS native CLI: {len(passed)} exact search cases, Russian/Kazakh/Unicode arguments, filters, provenance and ranking; different working directory')
