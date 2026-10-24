"""Small delta/setup builder. Only a new explicit frozen authority enables a distributable build."""
from __future__ import annotations
import copy,hashlib,json,os,shutil,stat,zipfile
from pathlib import Path
import delta
from portable import native,ordinary_file,plain_absolute,safe_relative,sha,load

HERE=Path(__file__).resolve().parent
MODULES={'assistant','database','documents','graph','inference','search','sources'}
CONTROL_PATHS=('Portable-Setup.ps1','Portable-Launch.ps1','portable-release.json','portable-files.json','.mini-ai-delta-common.ps1',
               'profiles/worker.cpu.json','profiles/worker.cuda.json','Установить-и-запустить.cmd','Запустить-на-процессоре.cmd','Запустить-на-NVIDIA.cmd')
def encoded(value):return (json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
def put(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
def row(path,relative=None):return {'path':relative or path.name,'bytes':path.stat().st_size,'sha256':sha(path)}
def ps(template,**pins):
    text=(HERE/template).read_text(encoding='utf-8-sig')
    for name,value in pins.items():text=text.replace('@@'+name+'@@',value)
    if '@@' in text:raise ValueError('Unsubstituted installer placeholder')
    return text.encode('utf-8-sig')
def command(script,arguments=''):
    return ('@echo off\r\nsetlocal\r\n"%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0'+script+'" '+arguments+' %*\r\nif errorlevel 1 (\r\n  echo Setup stopped. Read the message above.\r\n  pause\r\n  exit /b 1\r\n)\r\nendlocal\r\n').encode('ascii')
def authority(args,target_record,target_path,root):
    if not args.authority:raise ValueError('A new explicit immutable release authority is required; old freeze/fallback is insufficient')
    path=plain_absolute(args.authority);ordinary_file(path,path.parent);value=load(path)
    if value.get('state')!='release-runtime-frozen' or value.get('approved_for_distribution') is not True or value.get('human_review') is not False:
        raise ValueError('New release authority is not explicitly frozen/approved/automatically assessed')
    if value.get('configuration_id')!=args.configuration_id or value.get('release_tag')!=args.target_tag or plain_absolute(Path(value.get('runtime_root','')))!=root:
        raise ValueError('Release authority config/tag/runtime differs')
    reference=value.get('runtime_inventory',{})
    if plain_absolute(Path(reference.get('path','')))!=target_path or reference.get('bytes')!=target_path.stat().st_size or reference.get('sha256')!=sha(target_path):
        raise ValueError('Release authority full inventory differs')
    for key in ('app_sha256','model_manifest_sha256','corpus_sha256'):
        if value.get(key)!=target_record.get(key):raise ValueError('Release authority '+key+' differs')
    notice=value.get('quality_notice')
    if not isinstance(notice,str) or not notice.strip() or value.get('quality_acceptance') not in ('demonstrated','not-demonstrated'):
        raise ValueError('Explicit honest quality state/notice required')
    return {'role':'approved-frozen-runtime','notice':notice,'quality_acceptance':value['quality_acceptance'],'authority_sha256':sha(path)}

def build(args):
    base_path,target_path,assets_path,setup_path,out=[plain_absolute(p) for p in (args.base_inventory,args.target_inventory,args.base_assets,args.base_setup,args.out)]
    if sha(base_path)!=delta.BASE_INVENTORY_SHA or sha(assets_path)!=delta.BASE_ASSETS_SHA:raise ValueError('Pinned foundation fingerprints differ')
    base_record,target_record,assets_record=load(base_path),load(target_path),load(assets_path)
    base,target=delta.inventory(base_record),delta.inventory(target_record)
    public_base=delta.published_setup(setup_path,assets_record,base_record,base)
    root=plain_absolute(Path(target_record['root']));base_root=plain_absolute(Path(base_record['root']))
    delta.independent_output(out);delta.independent_output(root)
    if out.exists() or out==root or root in out.parents or out==base_root or base_root in out.parents:raise ValueError('New independent output is required')
    if args.target_tag==delta.BASE_TAG or not __import__('re').fullmatch(r'v[0-9A-Za-z][0-9A-Za-z._-]{0,95}',args.target_tag):raise ValueError('Distinct safe target tag required')
    if not __import__('re').fullmatch(r'[a-z0-9][a-z0-9._-]{0,95}',args.configuration_id):raise ValueError('Safe new configuration id required')
    decision=authority(args,target_record,target_path,root)
    for key,path in (('app_sha256','Mini-AI.exe'),('model_manifest_sha256','model/worker.json'),('corpus_sha256','db/index.sqlite')):
        if path not in target or target_record.get(key)!=target[path]['sha256']:
            raise ValueError('Frozen stage fingerprint differs from its inventory: '+key)
    if set(base)-set(target):raise ValueError('Preserve all old runtime paths; retired paths need a separately reviewed migration')
    delta.inspect_target(root,target)
    folded={p.casefold():p for p in base}
    for path in target:
        if path.casefold() in folded and path!=folded[path.casefold()]:raise ValueError('Case-only migration is unsupported')
        if path.split('/')[0] not in ('Mini-AI.exe','vcruntime140.dll','vcruntime140_1.dll','native','licenses','model','docs','db'):raise ValueError('Reserved runtime namespace')
    module_ids={r['id'] for r in load(root/'licenses/module-inventory.json')}
    if module_ids!=MODULES:raise ValueError('Only the complete seven-module application is supported')
    changed=[p for p in sorted(target) if p not in base or base[p]!=target[p]]
    needed=sum(target[p]['bytes'] for p in changed)+16*1024**2
    if shutil.disk_usage(Path('C:/')).free<20*1024**3:raise ValueError('Keep at least 20 GiB free on C')
    output_drive=out
    while not output_drive.exists():output_drive=output_drive.parent
    reserve=20*1024**3 if out.drive.casefold()=='c:' else 1024**3
    if shutil.disk_usage(output_drive).free-needed<reserve:raise ValueError('Not enough output space with the required reserve')
    for name,entry in target.items():
        p=ordinary_file(root/name,root)
        if p.stat().st_size!=entry['bytes'] or sha(p)!=entry['sha256']:raise ValueError('Full frozen target bytes differ: '+name)
    out.mkdir(parents=True);bundle=out/'bootstrap/Mini-AI';bundle.mkdir(parents=True)
    with zipfile.ZipFile(setup_path) as old:
        original={name[len('Mini-AI/'):]:old.read(name) for name in old.namelist() if name.startswith('Mini-AI/') and not name.endswith('/')}
    put(bundle/'foundation-setup.zip',setup_path.read_bytes())
    put(bundle/'base-files.json',original['portable-files.json'])
    base_meta=json.loads(original['portable-release.json'])
    base_profiles={}
    for mode in ('cpu','cuda'):
        name='base-profiles/worker.'+mode+'.json';put(bundle/name,original['profiles/worker.'+mode+'.json']);base_profiles[mode]=row(bundle/name,name)
    target_inventory={'schema_version':1,'configuration_id':args.configuration_id,'files':list(target.values())}
    put(bundle/'target-files.json',encoded(target_inventory))
    manifest=load(root/'model/worker.json')
    if manifest.get('schemaVersion')!=2 or manifest['backend']['kind']!='cuda' or not manifest.get('evidence'):raise ValueError('Full explicit CUDA/evidence model source required')
    if {r['path']:r['sha256'] for r in manifest['files']}['inference-worker.exe']!=target['model/inference-worker.exe']['sha256']:raise ValueError('Target worker inventory differs')
    new_profiles={}
    for mode in ('cpu','cuda'):
        name='profiles/worker.'+mode+'.json'
        if mode=='cuda':data=(root/'model/worker.json').read_bytes()
        else:
            cpu=copy.deepcopy(manifest);cpu['backend']={'kind':'cpu','device':0,'gpuMemoryBudgetBytes':0,'reserveFreeGpuMemoryBytes':0};data=encoded(cpu)
        put(bundle/name,data);new_profiles[mode]=row(bundle/name,name)
    public_target={'schema_version':1,'configuration_id':args.configuration_id,'repository':delta.REPOSITORY,'tag':args.target_tag,
                   'app_sha256':target_record['app_sha256'],'model_manifest_sha256':target['model/worker.json']['sha256'],
                   'corpus_sha256':target_record['corpus_sha256'],'inventory_sha256':sha(bundle/'target-files.json'),
                   'file_count':len(target),'unpacked_bytes':target_record['unpacked_bytes'],
                   'profiles':dict(default='cpu',**new_profiles),'deployment':decision}
    controls_dir=bundle/'install-controls';put(controls_dir/'portable-release.json',encoded(public_target));release_pin=sha(controls_dir/'portable-release.json')
    put(controls_dir/'portable-files.json',encoded(target_inventory))
    common_data=(HERE/'delta-common.ps1').read_text(encoding='utf-8-sig').encode('utf-8-sig');put(bundle/'delta-common.ps1',common_data);common_pin=sha(bundle/'delta-common.ps1')
    put(controls_dir/'.mini-ai-delta-common.ps1',common_data)
    put(controls_dir/'Portable-Setup.ps1',ps('delta-verify.ps1',RELEASE_SHA256=release_pin,COMMON_SHA256=common_pin))
    put(controls_dir/'Portable-Launch.ps1',ps('portable-launch.ps1',RELEASE_SHA256=release_pin))
    for mode in ('cpu','cuda'):put(controls_dir/('profiles/worker.'+mode+'.json'),(bundle/('profiles/worker.'+mode+'.json')).read_bytes())
    put(controls_dir/'Установить-и-запустить.cmd',command('Portable-Setup.ps1'))
    put(controls_dir/'Запустить-на-процессоре.cmd',command('Portable-Launch.ps1','-Mode cpu'))
    put(controls_dir/'Запустить-на-NVIDIA.cmd',command('Portable-Launch.ps1','-Mode cuda'))
    controls=[]
    for name in CONTROL_PATHS:
        entry=row(controls_dir/name,name);entry['source']='install-controls/'+name
        entry['allowed_before']=[] if name not in original else [{'bytes':len(original[name]),'sha256':hashlib.sha256(original[name]).hexdigest()}]
        controls.append(entry)
    payload_path=out/'Mini-AI.update.zip'
    with zipfile.ZipFile(payload_path,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as archive:
        for name in changed:
            source=ordinary_file(root/name,root);before=source.stat();digest=hashlib.sha256();count=0
            info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
            with source.open('rb') as inp,archive.open(info,'w',force_zip64=True) as dest:
                for data in iter(lambda:inp.read(1024**2),b''):dest.write(data);digest.update(data);count+=len(data)
            after=source.stat()
            if (before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_ino,after.st_size,after.st_mtime_ns) or count!=target[name]['bytes'] or digest.hexdigest()!=target[name]['sha256']:raise ValueError('Changed source bytes unstable/mismatched: '+name)
    owner={'schema_version':1,'configuration_id':args.configuration_id,'tag':args.target_tag,'scope':'Only this updater owns this transaction directory; preserve all backups.'}
    put(bundle/'update-owner.json',encoded(owner))
    meta={'schema_version':2,'configuration_id':args.configuration_id,'tag':args.target_tag,'repository':delta.REPOSITORY,
          'authority':decision,'owner_bytes':(bundle/'update-owner.json').stat().st_size,'owner_sha256':sha(bundle/'update-owner.json'),
          'base':{'tag':delta.BASE_TAG,'configuration_id':delta.BASE_CONFIGURATION,'file_count':len(base),'inventory_sha256':public_base['public_inventory_sha256'],
                  'profiles':base_profiles,'app_sha256':base_record['app_sha256'],
                  'setup':{'path':'foundation-setup.zip','bytes':setup_path.stat().st_size,'sha256':sha(setup_path),'url':f'https://github.com/{delta.REPOSITORY}/releases/download/{delta.BASE_TAG}/Mini-AI-setup.zip'}},
          'target':{'file_count':len(target),'inventory_sha256':sha(bundle/'target-files.json'),'app_sha256':target_record['app_sha256'],'profiles':new_profiles},
          'payload':{'name':payload_path.name,'bytes':payload_path.stat().st_size,'sha256':sha(payload_path),
                     'url':f'https://github.com/{delta.REPOSITORY}/releases/download/{args.target_tag}/{payload_path.name}','files':[target[name] for name in changed]},
          'control_updates':controls}
    put(bundle/'delta-release.json',encoded(meta));pin=sha(bundle/'delta-release.json')
    put(bundle/'Delta-Apply.ps1',ps('delta-apply.ps1',DELTA_SHA256=pin,COMMON_SHA256=common_pin));apply_pin=sha(bundle/'Delta-Apply.ps1')
    put(bundle/'Delta-Setup.ps1',ps('delta-setup.ps1',DELTA_SHA256=pin,COMMON_SHA256=common_pin,APPLY_SHA256=apply_pin))
    put(bundle/'Установить-и-запустить.cmd',command('Delta-Setup.ps1'))
    readme=('Mini-AI: установка и обновление\n\nРаспакуйте этот ZIP и откройте Установить-и-запустить.cmd.\n'
            'Новая установка создаётся в подпапке App; проверенная основа скачивается по закреплённым ссылкам, затем устанавливаются изменения.\n'
            'Для существующей установки: запустите Delta-Setup.ps1 с -InstallDirectory и полным путём к папке Mini-AI. Закройте приложение до обновления.\n'
            'Режим CPU/NVIDIA сохраняется. Для новой установки выбран CPU. Нужны свободная память и место под загрузку основы, установленную базу и небольшое обновление.\n'
            'Прежние заменённые файлы сохраняются в .mini-ai-updates; при прерывании повторите этот же установщик. Не смешивайте разные версии.\n'
            'Python, системная установка .NET, UAC и вход на сайт не требуются. Интернет нужен для недостающих файлов.\n\n'+decision['notice']+'\n')
    put(bundle/'README.txt',readme.encode('utf-8-sig'))
    setup_output=out/'Mini-AI-update-setup.zip'
    with zipfile.ZipFile(setup_output,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path in sorted(p for p in bundle.rglob('*') if p.is_file()):archive.write(path,'Mini-AI/'+path.relative_to(bundle).as_posix())
    assets=[{'name':p.name,'bytes':p.stat().st_size,'sha256':sha(p)} for p in (setup_output,payload_path)]
    sums=out/'SHA256SUMS.txt';put(sums,''.join(item['sha256']+'  '+item['name']+'\n' for item in assets).encode('ascii'));assets.append({'name':sums.name,'bytes':sums.stat().st_size,'sha256':sha(sums)})
    record={'schema_version':1,'state':'prepared-not-published','tag':args.target_tag,'configuration_id':args.configuration_id,
            'delta_metadata_sha256':pin,'authority':decision,'file_count':len(target),'runtime_changes':len(changed),
            'runtime_changed_logical_bytes':sum(target[p]['bytes'] for p in changed),'full_target_sha_verified_by_builder':True,
            'base_repacked_or_downloaded':False,'assets':assets,'source_stage_sha256':sha(target_path)}
    put(out/'publication-assets.json',encoded(record))
    print(json.dumps(record))
