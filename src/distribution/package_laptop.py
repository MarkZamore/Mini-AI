"""Package the verified runtime plus a pinned, no-admin first-run preparation script."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'src/build/laptop-release'
PACKAGE = OUT / 'package/Mini-AI'
TAG = 'v2026.10.15-laptop-ready'
APP_SHA = 'd5fe931323200a5809e28c7e0f3eabde2ed1cd2a4272577b180e27a151310202'

def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def main():
    assert sha(ROOT/'Mini-AI.exe') == APP_SHA, 'App differs from accepted local build'
    PACKAGE.mkdir(parents=True, exist_ok=True)
    included = set()
    def copy(source, relative):
        target=PACKAGE/relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source,target)
        included.add(relative)
    for name in ('Mini-AI.exe','vcruntime140.dll','vcruntime140_1.dll'):
        copy(ROOT/name,name)
    for folder in ('native','licenses'):
        for source in sorted((ROOT/folder).rglob('*')):
            if source.is_file():copy(source,source.relative_to(ROOT).as_posix())
    manifest=json.loads((ROOT/'model/worker.json').read_text(encoding='utf-8'))
    for entry in manifest['files']:
        source=ROOT/'model'/entry['path']
        assert sha(source)==entry['sha256'],entry['path']
        if entry['path']!=manifest['weights']['path']:
            copy(source,'model/'+entry['path'])
    copy(ROOT/'model/worker.json','model/worker.json')
    for name in ('Setup.cmd','Установить-и-запустить.cmd'):
        copy(ROOT/'src/distribution/Setup.cmd',name)
    # BOM makes Unicode source safe for Windows PowerShell 5.1.
    (PACKAGE/'Setup.ps1').write_text((ROOT/'src/distribution/Setup.ps1').read_text(encoding='utf-8'),encoding='utf-8-sig')
    included.add('Setup.ps1')
    source_inventory=ROOT/'src/build/model-preparation/downloads/EnterpriseAI-files.json'
    assert sha(source_inventory)=='6e45549454e6eac449d325f516addde87d36041fa22c6c6656f65170f370f06c'
    copy(source_inventory,'EnterpriseAI-files.json')
    assets=[]
    pins=[
        ('EnterpriseAI-data.zip',799252723,'8e2a0115d59ba208d7d14803e50f552f6df9f93f38c50c70f6ea1855d8ebe079'),
        ('EnterpriseAI-artifacts-00-3f.zip',1118973694,'f3aaf22aaab30cfe49432f44c2de2f2328001873dfc6ff3cb30564760aa5be37'),
        ('EnterpriseAI-artifacts-40-7f.zip',1306332636,'e99566a065f23e794bf885ffbd91306ebb4f39d7e753d1b73c58cbb7d6b29dd1'),
        ('EnterpriseAI-artifacts-80-bf.zip',1318743256,'87560dcae125accab771f44f3e18444b5300b78d618ac562b9b83a9ff9f35fad'),
        ('EnterpriseAI-artifacts-c0-ff.zip',1343730194,'7bf51c677f1f33194c98a168882db81d818bf8f41bf5ecdbb30ce22e80e35471'),
        ('model-weights.part01',1835008000,'4acc3aebee0bc8a554541d76950ccc8bed64749ddf22b210244b08a1788b9676'),
        ('model-weights.part02',1629047744,'5be094730ddc9f0a4270dcdb8010d3e1454155f88297ce52b128e30e0426a6f6'),
    ]
    for name,size,digest in pins:
        tag='v2026.10.15-model-interim-4b' if name.startswith('model-weights.') else 'v2026.10.15'
        assets.append({'name':name,'bytes':size,'sha256':digest,
            'url':f'https://github.com/MarkZamore/Mini-AI/releases/download/{tag}/{name}'})
    write_json(PACKAGE/'download-assets.json',assets);included.add('download-assets.json')
    instructions='''Mini-AI: приложение, документы и локальная модель 4B

1. Распакуйте ZIP полностью, желательно в короткий путь, например C:\\Mini-AI.
2. Откройте Установить-и-запустить.cmd (или Setup.cmd).
3. Дождитесь скачивания и проверки всех документов, артефактов и весов.
   Загрузка около 9,5 ГБ; на время подготовки нужно 25 ГБ свободного места.
   Приложение откроется автоматически. Авторизация на сайтах и права администратора не нужны.
4. В дальнейшем запускайте Mini-AI.exe. Интернет для работы помощника не нужен.

Нужны Windows 10/11 x64, CPU с AVX2 и не менее 8 ГБ свободной оперативной памяти.
Модель — исходная Qwen3.5-4B Q6_K, без местного дообучения. Первый ответ может
занять несколько минут. Небольшая модель и OCR могут неточно передавать формулировки;
сверяйте ответ с цитатами. Проверка на целевом ноутбуке ещё нужна.

Тестовые вопросы:
  Нормативные документы на базе какой организации?
  Что говорится об учебном плане?
  Что говорится про марсианский регламент 2099?
Для последнего вопроса ожидается сообщение об отсутствии источников.

Не переносите один EXE: сохраняйте native, licenses, обе DLL, docs, db и model.
downloads — проверенный кеш установочных загрузок, он не нужен для дальнейшей работы.
Набор содержит все 6535 файлов опубликованных документов/индекса/артефактов,
а не только сокращённую локальную копию разработчика. Старые EXE и инструкции
обучения из архивов данных не устанавливаются.

Для полностью автономной установки заранее положите семь файлов, перечисленных
в download-assets.json, в папку downloads. Их официальные ссылки и SHA указаны там.
Затем выполните: powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\\Setup.ps1 -Offline
Если файл отличается от ожидаемого, подготовка останавливается и сохраняет его.
Распаковывайте комплект в отдельную новую папку, не поверх другого набора данных.

Исходники и обновление: https://github.com/MarkZamore/Mini-AI
'''
    (PACKAGE/'Прочитай-меня.txt').write_text(instructions,encoding='utf-8-sig');included.add('Прочитай-меня.txt')
    write_json(PACKAGE/'runtime-files.json',{'app_sha256':APP_SHA,'files':[
        {'path':name,'bytes':(PACKAGE/name).stat().st_size,'sha256':sha(PACKAGE/name)} for name in sorted(included)]})
    included.add('runtime-files.json')
    archive=OUT/'Mini-AI-laptop-setup.zip'
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as z:
        for name in sorted(included):
            info=zipfile.ZipInfo('Mini-AI/'+name,date_time=(2026,10,15,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            with z.open(info,'w',force_zip64=True) as dest,(PACKAGE/name).open('rb') as source:
                shutil.copyfileobj(source,dest,1024*1024)
    (OUT/'SHA256SUMS.txt').write_text(sha(archive)+'  '+archive.name+'\n',encoding='ascii')
    write_json(OUT/'package-report.json',{'tag':TAG,'archive':archive.name,'bytes':archive.stat().st_size,
        'sha256':sha(archive),'runtime_files':len(included),'app_sha256':APP_SHA,'additional_download_bytes':sum(a['bytes'] for a in assets)})
    print((OUT/'package-report.json').read_text(),flush=True)

if __name__=='__main__':main()
