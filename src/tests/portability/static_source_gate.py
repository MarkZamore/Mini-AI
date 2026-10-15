"""Actual static EXE source-resolution regression using physical copied inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import uuid
from static_pe import assert_static_imports

ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT/'src/build/test-results'

def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--exe',type=Path,required=True);parser.add_argument("--report-prefix",default="dusty-yellow");args=parser.parse_args()
    original_exe=args.exe.resolve();assert_static_imports(original_exe)
    prefix=args.report_prefix
    if not prefix.isascii() or not prefix.replace('-', '').isalnum():raise ValueError('Invalid report prefix')
    folder=BUILD/(prefix+'-source-fixtures')/uuid.uuid4().hex
    folder.mkdir(parents=True);(folder/'db').mkdir()
    shutil.copyfile(original_exe,folder/original_exe.name)
    index=ROOT/'db/index.sqlite';index_hash=sha(index)
    shutil.copyfile(index,folder/'db/index.sqlite')
    def run(*arguments,code=0):
        child=subprocess.run([str(folder/original_exe.name),*arguments],cwd=ROOT.parent,capture_output=True,encoding='utf-8',timeout=30)
        if child.returncode!=code or child.stderr:raise AssertionError((arguments,child.returncode,child.stderr,child.stdout))
        return json.loads(child.stdout)
    hits=run('--search','академическая политика','10')
    hit=hits[0];ref=next(ref for ref in hit['source_refs'] if ref['artifact_id']=='original')
    fragment=run('--get-fragment',hit['fragment_id'])
    for key in ('fragment_id','document_id','text','source_refs','span','quality','warnings','source_sha256'):
        if fragment[key]!=hit[key]:raise AssertionError('Fragment/provenance changed: '+key)
    with sqlite3.connect(index.as_uri()+'?mode=ro',uri=True) as connection:
        stored=connection.execute('SELECT source_path FROM documents WHERE document_id=?',(hit['document_id'],)).fetchone()[0]
    original=(ROOT/'db'/stored).resolve()
    if not original.is_relative_to(ROOT/'docs'):raise AssertionError('Fixture selected source outside docs')
    original_hash=sha(original)
    copied=folder/'docs'/original.relative_to(ROOT/'docs')
    copied.parent.mkdir(parents=True);shutil.copyfile(original,copied)
    before=sha(copied)
    resolved=run('--resolve',json.dumps(ref,ensure_ascii=False))
    if not resolved['verified'] or Path(resolved['original_path']).resolve()!=copied.resolve():raise AssertionError('Exact original source not verified')
    if resolved['source_sha256']!=original_hash or resolved['locator']!=ref:raise AssertionError('Source identity/locator lost')
    verified_after=sha(copied)
    with copied.open('ab') as stream:stream.write(b'\nObserver-only changed source fixture\n')
    modified=sha(copied)
    refused=run('--resolve',json.dumps(ref,ensure_ascii=False),code=1)
    if refused['success'] or refused['error']['code']!='SOURCE_VERIFICATION_FAILED':raise AssertionError('Modified original accepted')
    if sha(copied)!=modified or sha(original)!=original_hash or sha(index)!=index_hash:raise AssertionError('Application changed original/fixture data')
    sidecars=[str(path) for path in folder.rglob('*') if path.name.endswith(('-wal','-shm','-journal','.dmp','.hprof'))]
    if sidecars:raise AssertionError('Unexpected sidecars')
    report={'success':True,'exe':str(original_exe),'exeSha256':sha(original_exe),'fixture':str(folder),'fragmentId':hit['fragment_id'],
        'exactFragmentAndLocator':True,'verifiedOriginalSha256':original_hash,'sourceUnchangedAfterVerification':before==verified_after,
        'modifiedSourceRejected':refused['error'],'indexUnchangedSha256':index_hash,'sidecars':sidecars,
        'limitations':['Physical copied PDF fixture; external viewer is not launched.','This source check does not verify transient filesystem operations or registry changes.']}
    (BUILD/(prefix+'-source-gate.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS static fragment/provenance, original PDF SHA and rejection after observer-only source alteration')

if __name__=='__main__':main()
