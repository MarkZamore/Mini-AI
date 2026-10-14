"""Download and unpack isolated tools; document data never leaves this machine."""
import concurrent.futures, hashlib, json, os, re, subprocess, sys, urllib.request
from pathlib import Path
from common import DB_ROOT, PYTHON, POPLER, CREATE_NO_WINDOW, atomic_json, sha256

ROOT = DB_ROOT / '_runtime'
DOWNLOADS = ROOT / 'downloads'
SOURCES = {
 '7zr.exe': 'https://github.com/ip7z/7zip/releases/download/26.04/7zr.exe',
 '7zip.exe': 'https://github.com/ip7z/7zip/releases/download/26.04/7z2604-x64.exe',
 'tesseract.exe': 'https://github.com/tesseract-ocr/tesseract/releases/download/5.5.3/tesseract-ocr-w64-setup-5.5.3.20260724.exe',
 'libreoffice.msi': 'https://download.documentfoundation.org/libreoffice/stable/26.2.6/win/x86_64/LibreOffice_26.2.6_Win_x86-64.msi',
 **{lang+'.traineddata': 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/'+lang+'.traineddata' for lang in ['rus','kaz','eng']},
 'osd.traineddata': 'https://raw.githubusercontent.com/tesseract-ocr/tessdata/main/osd.traineddata',
}

def download(item):
    name, url = item; path = DOWNLOADS / name
    if path.exists() and path.stat().st_size: return name, path
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=60) as response, open(str(path)+'.part','wb') as target:
                expected = response.headers.get('Content-Length')
                while block := response.read(1024*1024): target.write(block)
            if expected and os.path.getsize(str(path)+'.part') != int(expected): raise ValueError('Truncated dependency')
            os.replace(str(path)+'.part',path)
            print('Downloaded '+name+' ('+str(path.stat().st_size)+' bytes)',flush=True)
            return name,path
        except Exception as exc: last = exc
    raise RuntimeError(name+': '+str(last))

def run(args, timeout=600):
    result = subprocess.run([str(x) for x in args], capture_output=True, timeout=timeout, creationflags=CREATE_NO_WINDOW)
    if result.returncode: raise RuntimeError(str(args[0])+': '+result.stdout.decode('utf-8','replace')[-1000:]+' '+result.stderr.decode('utf-8','replace')[-1000:])
    return result.stdout.decode('utf-8','replace').strip()

def main():
    DOWNLOADS.mkdir(parents=True,exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: paths=dict(pool.map(download,SOURCES.items()))
    expected_msi='f9877032fd908beb9c0ddf06df4af5c2e85f419c42e14876c4cce5aae5fb2660'
    if sha256(paths['libreoffice.msi']) != expected_msi: raise RuntimeError('LibreOffice published checksum mismatch')
    if not (ROOT/'7zip'/'7z.exe').exists():
        run([paths['7zr.exe'],'x',paths['7zip.exe'],'-y','-o'+str(ROOT/'7zip')])
    if not (ROOT/'tesseract'/'tesseract.exe').exists():
        run([ROOT/'7zip'/'7z.exe','x',paths['tesseract.exe'],'-y','-o'+str(ROOT/'tesseract')])
    # Administrative extraction creates a private application image, not a desktop installation.
    if not (ROOT/'libreoffice'/'program'/'soffice.exe').exists():
        run(['msiexec.exe','/a',paths['libreoffice.msi'],'/qn','TARGETDIR='+str(ROOT/'libreoffice')],timeout=900)
    tess = ROOT/'tesseract'/'tesseract.exe'
    if not tess.exists(): raise RuntimeError('Tesseract extraction did not produce executable')
    data=ROOT/'tessdata'; data.mkdir(exist_ok=True)
    for lang in ['rus','kaz','eng','osd']:
        path=data/(lang+'.traineddata')
        if not path.exists(): path.write_bytes(paths[lang+'.traineddata'].read_bytes())
    import importlib.metadata as metadata
    packages={name:metadata.version(name) for name in ['pypdf','pdfplumber','lxml','openpyxl','xlrd','jsonschema','snowballstemmer','opencv-python-headless']}
    info={'created_at':__import__('common').utc(),'python':str(PYTHON),'python_version':sys.version,'poppler':str(POPLER),
          'tesseract':str(tess),'libreoffice':str(ROOT/'libreoffice'/'program'/'soffice.exe'),'tessdata':str(data),
          'versions':{'tesseract':run([tess,'--version']).splitlines()[0],'libreoffice':run([ROOT/'libreoffice'/'program'/'soffice.com','--version'])},
          'packages':packages,'downloads':{n:{'url':SOURCES[n],'sha256':sha256(p),'size':p.stat().st_size} for n,p in paths.items()}}
    # Compare the MSI to the checksum published by its source when available.
    info['libreoffice_published_sha256_verified']=True
    atomic_json(ROOT/'tools.json',info)
    print(json.dumps(info,ensure_ascii=False),flush=True)

if __name__=='__main__': main()
