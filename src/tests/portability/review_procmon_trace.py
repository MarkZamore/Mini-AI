"""Offline scoped trace review. Does not launch tools, resolve network symbols or change raw evidence."""
import argparse, collections, csv, datetime, hashlib, json, ntpath, pathlib, re, xml.etree.ElementTree as ET

MUTATIONS = re.compile(r'^(WriteFile|SetEndOfFileInformationFile|SetAllocationInformationFile|SetDispositionInformationFile|SetRenameInformationFile|SetBasicInformationFile|SetSecurityFile|SetEAFile|RegSetValue|RegCreateKey|RegDeleteKey|RegDeleteValue|RegRenameKey|RegSetKeySecurity|RegLoadKey|RegUnloadKey|RegRestoreKey|RegReplaceKey|RegSaveKey)$', re.I)
ACCESS = re.compile(r'(?:^|,\s*)Desired Access:\s*(.*?)(?=,\s*(?:Disposition|Options|Attributes|ShareMode|AllocationSize|OpenResult|Impersonating|EaLength|SecurityQoS):|$)', re.I)
WRITE_ACCESS = re.compile(r'Generic Write|Write Data|Append Data|Write Attributes|Write EA|Delete|All Access', re.I)
CREATING = re.compile(r'Disposition:\s*(?:Create|OpenIf|Overwrite|OverwriteIf|Supersede)(?:,|$)', re.I)
IPC = re.compile(r'^(?:\\Device\\NamedPipe(?:\\|$)|\\Device\\ConDrv(?:\\|$)|\\\\\.\\pipe(?:\\|$))', re.I)

def intent(row):
    op, detail = row.get('Operation',''), row.get('Detail','')
    if op == 'CreateFile':
        access = ACCESS.search(detail)
        access = access.group(1) if access else ''
        return bool(WRITE_ACCESS.search(access) or CREATING.search(detail) or re.search(r'Options:.*Delete On Close', detail, re.I) or re.search('Maximum Allowed', access, re.I))
    return bool(MUTATIONS.match(op))

def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b''): h.update(block)
    return h.hexdigest()

def windows_time(text):
    if not text or text == '0': return None
    seconds = (int(text) - 116444736000000000) / 10000000
    return datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).isoformat()

def fields(element): return {child.tag: child.text or '' for child in element if child.tag not in ('modulelist','stack')}

def canonical_image(value):
    if value.startswith('\\\\?\\UNC\\'): value='\\\\'+value[8:]
    elif value.startswith(('\\\\?\\','\\??\\')): value=value[4:]
    return ntpath.normcase(ntpath.normpath(value))

def discover_workers(folder, owners):
    """Attribute planned workers from successful owned-parent Process Create.

    A PID alone or an inference-worker filename alone never establishes ownership.
    Existing code hashes must match the exact reviewed plan path. Unplanned or
    altered children remain visible as unresolved discoveries, not exemptions.
    """
    plan_path=folder/'plan.json'
    plan=json.loads(plan_path.read_text(encoding='utf-8-sig')) if plan_path.exists() else {}
    planned={}; hash_evidence=[]
    for item in plan.get('applicationFiles',[]):
        if ntpath.basename(item['path']).lower()!='inference-worker.exe': continue
        current=pathlib.Path(item['path']); actual=digest(current) if current.is_file() else None
        verified=actual is not None and actual.lower()==item['sha256'].lower()
        planned[canonical_image(item['path'])]=dict(item,actualSha256=actual,verified=verified)
        hash_evidence.append(dict(path=item['path'],plannedSha256=item['sha256'],actualSha256=actual,verified=verified))
    discoveries=[]; unverified=[]; worker_pids=set(); seen=set()
    # ProcMon CSV is normally chronological. Repeat ownership expansion for a
    # reordered export instead of silently losing a child of a controlled worker.
    changed=True
    while changed:
        changed=False
        with (folder/'trace.csv').open(encoding='utf-8-sig',newline='') as source:
            for number,row in enumerate(csv.DictReader(source),1):
                pid=int(row['PID'])
                if row['Operation']!='Process Create' or row['Result']!='SUCCESS' or pid not in owners: continue
                if row['Process Name'].lower()!=owners[pid]['processName'].lower(): continue
                path=row['Path']
                if ntpath.basename(path).lower()!='inference-worker.exe': continue
                match=re.search(r'(?:^|,\s*)PID:\s*(\d+)',row['Detail'])
                if not match: continue
                child=int(match.group(1)); identity=(number,pid,child,canonical_image(path))
                if identity in seen: continue
                reviewed=planned.get(canonical_image(path))
                evidence=dict(csvRow=number,parentPid=pid,pid=child,exe=path,detail=row['Detail'],parentScenario=owners[pid]['name'])
                if not reviewed or not reviewed['verified']:
                    evidence['reason']='Exact child path/hash is not verified against plan.applicationFiles'
                    unverified.append(evidence);seen.add(identity);continue
                evidence.update(plannedSha256=reviewed['sha256'],actualSha256=reviewed['actualSha256'],attribution='successful-owned-parent-create-and-exact-planned-code-hash')
                if child in owners and child not in worker_pids:
                    evidence['reason']='Child PID collides with a recorded application case; inspect lifetime before attribution'
                    unverified.append(evidence);seen.add(identity);continue
                owners[child]=dict(pid=child,processName='inference-worker.exe',name=owners[pid]['name']+' / worker',exe=path,parentPid=pid,kind='controlled-worker')
                worker_pids.add(child);discoveries.append(evidence);seen.add(identity);changed=True
    return worker_pids, discoveries, unverified, hash_evidence

def run(folder):
    cases = json.loads((folder/'cases.json').read_text(encoding='utf-8-sig'))
    owners = {int(case['pid']): case for case in cases}
    worker_pids, worker_discoveries, unverified_workers, worker_hashes=discover_workers(folder,owners)
    transport_parents={item['parentPid'] for item in worker_discoveries}
    transport_pids=worker_pids|transport_parents
    raw = folder/'trace.pml'
    before = digest(raw)
    counters = collections.defaultdict(collections.Counter)
    scoped = []; candidate_keys = collections.Counter(); candidate_kinds={}; successful_pipe_bytes=collections.Counter()
    mapping_details=collections.Counter(); handle_metadata=collections.Counter(); sidecar_operations=collections.Counter(); pipe_results=collections.Counter(); owned_rows=0
    with (folder/'trace.csv').open(encoding='utf-8-sig',newline='') as source:
        for number,row in enumerate(csv.DictReader(source),1):
            pid = int(row['PID'])
            if pid not in owners or row['Process Name'].lower() != owners[pid]['processName'].lower(): continue
            owned_rows+=1
            counters[pid][row['Operation']] += 1
            if row['Operation']=='CreateFileMapping':mapping_details[row['Detail']]+=1
            if row['Operation']=='RegSetInfoKey':handle_metadata[row['Detail']]+=1
            if re.search(r'-(journal|wal|shm)$',row['Path']):sidecar_operations[row['Operation']+' / '+row['Result']]+=1
            if row['Operation']=='WriteFile':pipe_results[row['Result']]+=1
            if row['Operation']=='WriteFile' and row['Result']=='SUCCESS' and IPC.match(row['Path']):
                # Procmon localizes digit grouping (this ASUS uses U+00A0, not comma).
                length=re.search(r'Length:\s*([0-9,\s\u00a0\u202f]+)',row['Detail'])
                if length:successful_pipe_bytes[pid]+=int(re.sub(r'\D','',length.group(1)))
            ipc=bool(IPC.match(row['Path']));network=row['Operation'].startswith(('TCP ','UDP '))
            if intent(row) or network or (pid in transport_pids and ipc):
                kind='model-worker-transport-ipc' if ipc and pid in transport_pids else 'unresolved-ipc' if ipc else 'network-event' if network else 'candidate-write'
                row['csvRow'] = number;row['scenario'] = owners[pid]['name'];row['kind'] = kind
                row['scopeRole']='controlled-worker' if pid in worker_pids else 'application'
                scoped.append(row)
                key=(str(pid),row['Operation'],row['Path'],row['Result'],row['Detail'])
                candidate_keys[key] += 1;candidate_kinds[key]=kind
    indexes = {}; all_processes = []; kernel_modules = {}; direct_modules = collections.defaultdict(list)
    event_number = 0; scoped_events=[]
    # Clear completed process/event trees to keep the 2.7 GB raw stack XML bounded in memory.
    with (folder/'trace-stacks.xml').open('rb') as source:
        for _,el in ET.iterparse(source,events=('end',)):
            if el.tag == 'process':
                data = fields(el);pid = int(data.get('ProcessId','-1'));index = data.get('ProcessIndex')
                indexes[index] = data;all_processes.append(data)
                for mod in el.findall('modulelist/module'):
                    m=fields(mod);base=int(m.get('BaseAddress','0'),0);size=int(m.get('Size','0'),0)
                    m.update(base=base,size=size)
                    if base >= 0xffff800000000000: kernel_modules[(base,size,m.get('Path'))]=m
                    if pid in owners: direct_modules[index].append(m)
                el.clear()
            elif el.tag == 'event':
                event_number += 1
                data=fields(el);pid=int(data.get('PID','-1'))
                key=(str(pid),data.get('Operation'),data.get('Path'),data.get('Result'),data.get('Detail'))
                if pid in owners and candidate_keys[key]:
                    data['xmlEvent']=event_number;data['scenario']=owners[pid]['name'];data['kind']=candidate_kinds[key]
                    data['scopeRole']='controlled-worker' if pid in worker_pids else 'application'
                    data['rawStack']=[fields(frame) for frame in el.findall('stack/frame')]
                    scoped_events.append(data)
                el.clear()
    kernels = list(kernel_modules.values())
    for event in scoped_events:
        modules=direct_modules.get(event['ProcessIndex'],[])+kernels
        decoded=[]
        for frame in event['rawStack']:
            address=int(frame.get('address','0'),0)
            matches=[m for m in modules if m['base'] <= address < m['base']+m['size']]
            if matches:
                match=min(matches,key=lambda m:m['size'])
                decoded.append(dict(frame,module=match['Path'],offset=hex(address-match['base']),company=match.get('Company'),version=match.get('Version')))
            else: decoded.append(dict(frame,module=None))
        event['stack']=decoded
        names=[pathlib.PureWindowsPath(f['module']).name.lower() for f in decoded if f.get('module')]
        event['stackModules']=list(dict.fromkeys(names))
        own_images={pathlib.PureWindowsPath(owner.get('exe',owner['processName'])).name.lower() for owner in owners.values()}
        ownframes=[f for f in decoded if f.get('module') and (pathlib.PureWindowsPath(f['module']).name.lower() in own_images or pathlib.PureWindowsPath(f['module']).name.lower().startswith(('enterpriseai-','swt-')))]
        event['hasApplicationOrControlledDllFrame']=bool(ownframes)
        if event['Operation']=='RegSetValue' and '/services/bam/state/usersettings/' in event['Path'].lower().replace(chr(92),'/') and 'bam.sys' in names and not ownframes:
            event['attribution']='windows-kernel-bam-record';event['reason']='Exact BAM registry path, raw kernel stack resolves to bam.sys and has no application/SWT/JNI frame; no blanket system DLL exemption.'
        elif event['kind']=='model-worker-transport-ipc':
            event['attribution']='model-worker-transport-requires-anonymous-handle-and-protocol-review';event['reason']='Parent can write model protocol stdin and read worker stdout/stderr; worker has its own stdout/stderr. Parent captured stdout byte count cannot identify these handles or establish a pass. Review native sources, anonymous handle creation and raw stacks.'
        elif event['kind']=='unresolved-ipc':
            event['attribution']='anonymous-stdio-compatible-requires-handle-correlation';event['reason']='Observer cases launched ordinary processes with redirected stdout/stderr; base NamedPipe path alone is insufficient to identify the handle.'
        elif event['kind']=='network-event':event['attribution']='unresolved-network'
        else: event['attribution']='unresolved'
    processes=[];children=[]
    for data in all_processes:
        pid=int(data.get('ProcessId','-1'));parent=int(data.get('ParentProcessId','-1'))
        if pid in owners or parent in owners:
            item={k:v for k,v in data.items() if k in ('ProcessIndex','ProcessId','ParentProcessId','ParentProcessIndex','Integrity','ImagePath','CommandLine','ProcessName','Is64bit')}
            item['createdAtUtc']=windows_time(data.get('CreateTime'));item['finishedAtUtc']=windows_time(data.get('FinishTime'))
            if pid in owners:item['scenario']=owners[pid]['name'];item['scopeRole']='controlled-worker' if pid in worker_pids else 'application';processes.append(item)
            if parent in owners:children.append(item)
    network=[]
    with (folder/'trace.csv').open(encoding='utf-8-sig',newline='') as source:
        for row in csv.DictReader(source):
            if int(row['PID']) in owners and row['Operation'].startswith(('TCP ','UDP ')):network.append(row)
    after=digest(raw)
    stdio=[]
    for pid,case in owners.items():
        transport=pid in transport_pids
        expected=None if transport else len((case.get('stdout','')+case.get('stderr','')).encode('utf-8'))
        item=dict(pid=pid,scenario=case['name'],expectedUtf8Bytes=expected,observedSuccessfulPipeBytes=successful_pipe_bytes[pid],correlationEligible=not transport,
            role='model-worker-transport-requires-raw-review' if transport else 'redirected-parent-stdout-stderr')
        item['bytesMatch']=None if transport else expected==item['observedSuccessfulPipeBytes']
        stdio.append(item)
    report=dict(suite='Offline owned-process raw-stack review',pmlSha256=before,rawPmlUnchanged=before==after,caseCount=len(cases),ownedOperationCounts={str(pid):dict(count) for pid,count in counters.items()},scopedCandidateRows=len(scoped),nonIpcCandidateRows=sum(r['kind']=='candidate-write' for r in scoped),ipcRows=sum(r['kind'] in ('unresolved-ipc','model-worker-transport-ipc') for r in scoped),candidateRows=scoped,stackEvents=scoped_events,processes=processes,directChildren=children,anonymousStdioCorrelation=stdio,applicationNetworkEvents=network,workerProcesses=worker_discoveries,unverifiedWorkerDiscoveries=unverified_workers,workerCodeHashes=worker_hashes,ownedProcessModules={index:modules for index,modules in direct_modules.items()},attributionCounts=dict(collections.Counter(e['attribution'] for e in scoped_events)),unknownStackFrameCount=sum(f.get('module') is None for e in scoped_events for f in e['stack']),scopeQualification='REQUIRES_REVIEW_NOT_GLOBAL_PASS',limitations=['No symbol server or network symbol resolution used','Module ranges identify code ownership, not individual private function names','Raw PML preserved; this report contains only owned-process candidates and direct children','Controlled model transport is not inferred safe from aggregate pipe byte counts; native sources, anonymous handle identity and raw stacks require review','PID/image create attribution is provisional until XML lifetime and parent indices are reviewed'])
    eligible_stdio=[item for item in stdio if item['correlationEligible']]
    report.update(stdioByteCorrelationPassed=None if worker_pids else all(item['bytesMatch'] for item in eligible_stdio),eligibleParentStdioByteCorrelationPassed=all(item['bytesMatch'] for item in eligible_stdio),stdioByteCorrelationScope='eligible parent stdio only; excludes every controlled worker and its protocol-writing parent',workerTransportReviewRequired=bool(worker_pids),workerTransportIpcRows=sum(row['kind']=='model-worker-transport-ipc' for row in scoped),workerOwnedCsvRowCount=sum(sum(counters[pid].values()) for pid in worker_pids),ownedCsvRowCount=owned_rows,fileMappingDetailCounts=dict(mapping_details),registryHandleMetadataCounts=dict(handle_metadata),sqliteSidecarOperationCounts=dict(sidecar_operations),pipeResultCounts=dict(pipe_results))
    (folder/'owned-stack-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:report[k] for k in ('rawPmlUnchanged','caseCount','scopedCandidateRows','nonIpcCandidateRows','ipcRows','attributionCounts','unknownStackFrameCount')},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('run_directory',type=pathlib.Path);args=parser.parse_args();run(args.run_directory.resolve())
