param(
    [ValidateSet('Prepare','Capture','Observer','Analyze')][string]$Mode='Prepare',
    [string]$SourceRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$RunDirectory,
    [string]$Configuration,
    [ValidateSet('native','static')][string]$Profile='native',
    [string]$Executable,
    [string]$AdditionalScenariosJson,
    [string]$FatalProbeExecutable,
    [switch]$ConfigurationVerified,
    [switch]$AcceptSysinternalsLicense,
    [int]$CoordinatorPid=0,
    [ValidateRange(30,300)][int]$CaptureSeconds=150
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7){throw 'Developer observer workflow requires PowerShell 7; the distributed application does not.'}
$source=[IO.Path]::GetFullPath($SourceRoot)
$project=Split-Path -Parent $source
$build=[IO.Path]::GetFullPath((Join-Path $source 'build'))
$tool=[IO.Path]::GetFullPath((Join-Path $build 'tools/procmon/Procmon64.exe'))
function Elevated { ([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) }
function InBuild([string]$Path){$full=[IO.Path]::GetFullPath($Path);if(!$full.StartsWith($build+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw "Observer output must be under src/build: $full"};return $full}
function SaveJson([string]$Path,$Value){$Value|ConvertTo-Json -Depth 16|Set-Content -LiteralPath (InBuild $Path) -Encoding utf8}
function Quote([string]$Value){if($Value.Contains('"')){throw 'A Windows argument cannot contain quotation marks here'};return '"'+$Value+'"'}
function VerifyTool {
    if(!(Test-Path -LiteralPath $tool -PathType Leaf)){throw 'Download the official ProcessMonitor.zip to src/build/tools/procmon first.'}
    $signature=Get-AuthenticodeSignature -LiteralPath $tool
    if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation'){throw 'Procmon64.exe must have a valid Microsoft Authenticode signature.'}
    return @{path=$tool;sha256=(Get-FileHash -LiteralPath $tool -Algorithm SHA256).Hash.ToLowerInvariant();signer=$signature.SignerCertificate.Subject;version=(Get-Item -LiteralPath $tool).VersionInfo.FileVersion}
}
function ExistingProcmon {
    $session=(Get-Process -Id $PID).SessionId
    return @(Get-Process -Name Procmon,Procmon64,Procmon64a -ErrorAction SilentlyContinue|Where-Object SessionId -EQ $session)
}
function LaunchPipe([string]$Exe,[string[]]$Arguments,[string]$Directory) {
    $info=[Diagnostics.ProcessStartInfo]::new($Exe);$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.WorkingDirectory=$Directory;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $info.StandardOutputEncoding=[Text.Encoding]::UTF8;$info.StandardErrorEncoding=[Text.Encoding]::UTF8
    foreach($argument in $Arguments){$info.ArgumentList.Add($argument)}
    return [Diagnostics.Process]::Start($info)
}
if(!$RunDirectory){$auditGroup=if($Profile -eq 'static'){'static-procmon'}else{'procmon'};$RunDirectory=Join-Path $build ('test-results/'+$auditGroup+'/'+[Guid]::NewGuid().ToString('N'))}
$run=InBuild $RunDirectory
if($Mode -eq 'Prepare'){
    if(Test-Path -LiteralPath $run){throw 'Use a new audit run directory; existing evidence is never overwritten by Prepare.'}
    New-Item -ItemType Directory -Force -Path $run,(Join-Path $run 'working')|Out-Null
    $verifiedTool=VerifyTool
    $exe=if($Executable){[IO.Path]::GetFullPath($Executable)}else{Join-Path $project 'EnterpriseAI.exe'};if(!(Test-Path -LiteralPath $exe)){throw 'Build and review the actual EnterpriseAI.exe before preparing the observer run.'}
    if($Profile -eq 'static'){
        $peObserver=Join-Path $PSScriptRoot 'static_pe.py'
        $python=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
        & $python -B -c 'import sys;sys.path.insert(0,sys.argv[1]);from static_pe import assert_static_imports;assert_static_imports(sys.argv[2])' $PSScriptRoot $exe
        if($LASTEXITCODE -ne 0){throw 'Static audit refuses an executable with non-Windows PE imports'}
    }
    $fixtures=Join-Path $run 'fixtures'
    foreach($name in @('missing-all','corrupt-db','missing-model-folder')){
        $fixture=Join-Path $fixtures $name
        New-Item -ItemType Directory -Force -Path $fixture|Out-Null
        Copy-Item -LiteralPath $exe -Destination (Join-Path $fixture 'EnterpriseAI.exe')
        if($Profile -eq 'native'){
        New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'native')|Out-Null
        foreach($crt in @('VCRUNTIME140.dll','VCRUNTIME140_1.dll')){
            $originalCrt=Join-Path $project $crt
            if(!(Test-Path -LiteralPath $originalCrt -PathType Leaf)){throw "Required app-local CRT is absent: $crt"}
            Copy-Item -LiteralPath $originalCrt -Destination (Join-Path $fixture $crt)
        }
        foreach($dll in Get-ChildItem -LiteralPath (Join-Path $project 'native') -Filter *.dll){Copy-Item -LiteralPath $dll.FullName -Destination (Join-Path $fixture ('native/'+$dll.Name))}
        }
        if($name -eq 'corrupt-db'){
            New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'db')|Out-Null
            [IO.File]::WriteAllBytes((Join-Path $fixture 'db/index.sqlite'),[Text.Encoding]::ASCII.GetBytes('not a sqlite database'))
        }
        if($name -eq 'missing-model-folder'){
            New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'db')|Out-Null
            # A physical copy: a tested defect must not be able to mutate the original via a hardlink/junction.
            Copy-Item -LiteralPath (Join-Path $project 'db/index.sqlite') -Destination (Join-Path $fixture 'db/index.sqlite')
        }
    }
    # An un-published static image has no adjacent data. Exercise its identical
    # bytes in a physical read-only dataset fixture, never an originals junction.
    $dataExe=if($Profile -eq 'static'){Join-Path $fixtures 'missing-model-folder/EnterpriseAI.exe'}else{$exe}
    $scenarios=@(
        @{name='native-status';exe=$dataExe;arguments=@('--status');timeoutSeconds=15;expectedExitCode=0},
        @{name='native-search';exe=$dataExe;arguments=@('--search','Академическая политика','10');timeoutSeconds=15;expectedExitCode=0},
        @{name='native-graph';exe=$dataExe;arguments=@('--graph');timeoutSeconds=20;expectedExitCode=0},
        @{name='native-ui-smoke-missing-worker';exe=$dataExe;arguments=@('--ui-smoke');timeoutSeconds=15;expectedExitCode=0},
        @{name='missing-all-folders';exe=(Join-Path $fixtures 'missing-all/EnterpriseAI.exe');arguments=@('--ui-smoke');timeoutSeconds=15;expectedExitCode=0},
        @{name='corrupt-db';exe=(Join-Path $fixtures 'corrupt-db/EnterpriseAI.exe');arguments=@('--search','документы','10');timeoutSeconds=15;expectedExitCode=1},
        @{name='missing-model-folder';exe=(Join-Path $fixtures 'missing-model-folder/EnterpriseAI.exe');arguments=@('--ui-smoke');timeoutSeconds=15;expectedExitCode=0}
    )
    $fatal=Join-Path $build 'test-results/fatal-probe/CrashProbe.exe';$fatalDll=Join-Path $build 'test-results/fatal-probe/fatal-probe.dll'
    if($Profile -eq 'native' -and (Test-Path -LiteralPath $fatal) -and (Test-Path -LiteralPath $fatalDll)){
        $scenarios+=@{name='local-jni-fatal-probe';exe=$fatal;arguments=@($fatalDll,'fatal');timeoutSeconds=15;expectedNonzero=$true}
        $scenarios+=@{name='local-caught-oom-probe';exe=$fatal;arguments=@($fatalDll,'oom');timeoutSeconds=15;expectedExitCode=0}
    }
    if($Profile -eq 'static' -and $FatalProbeExecutable){
        $fatal=InBuild $FatalProbeExecutable
        & $python -B -c 'import sys;sys.path.insert(0,sys.argv[1]);from static_pe import assert_static_imports;assert_static_imports(sys.argv[2])' $PSScriptRoot $fatal
        if($LASTEXITCODE -ne 0){throw 'Fatal probe must also have no non-Windows dependencies'}
        $scenarios+=@{name='static-fatal-probe';exe=$fatal;arguments=@('fatal');timeoutSeconds=15;expectedNonzero=$true}
        $scenarios+=@{name='static-caught-oom-probe';exe=$fatal;arguments=@('oom');timeoutSeconds=15;expectedExitCode=0}
    }
    if($AdditionalScenariosJson){
        $extra=Get-Content -LiteralPath (InBuild $AdditionalScenariosJson) -Raw|ConvertFrom-Json
        foreach($scenario in @($extra)){
            $scenarioExe=InBuild $scenario.exe
            if(!(Test-Path -LiteralPath $scenarioExe -PathType Leaf) -or !$scenario.name -or $scenario.timeoutSeconds -lt 1 -or $scenario.timeoutSeconds -gt 60){throw 'Invalid additional observer scenario'}
            if($Profile -eq 'static'){
                & $python -B -c 'import sys;sys.path.insert(0,sys.argv[1]);from static_pe import assert_static_imports;assert_static_imports(sys.argv[2])' $PSScriptRoot $scenarioExe
                if($LASTEXITCODE -ne 0){throw 'Additional scenario imports a non-Windows DLL'}
            }
            $scenarios+=$scenario
        }
    }
    $codeFiles=@(Get-Item -LiteralPath $exe)
    if($Profile -eq 'native'){$codeFiles+=@(Get-ChildItem -LiteralPath $project -File -Filter *.dll)+@(Get-ChildItem -LiteralPath (Join-Path $project 'native') -File -Filter *.dll)}
    foreach($scenario in $scenarios){$codeFiles+=Get-Item -LiteralPath $scenario.exe; $modelFolder=Join-Path (Split-Path -Parent $scenario.exe) 'model';if(Test-Path -LiteralPath $modelFolder){$codeFiles+=@(Get-ChildItem -LiteralPath $modelFolder -Recurse -File -Filter *.exe)}}
    $codeFiles=@($codeFiles|Sort-Object FullName -Unique)
    $applicationFiles=@($codeFiles|ForEach-Object {@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
    $plan=@{schemaVersion=1;profile=$Profile;applicationExecutable=$exe;preparedAt=[DateTime]::UtcNow.ToString('o');windowsVersion=[Environment]::OSVersion.VersionString;applicationSha256=(Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant();applicationFiles=$applicationFiles;originalIndexSha256=(Get-FileHash -LiteralPath (Join-Path $project 'db/index.sqlite')).Hash.ToLowerInvariant();tool=$verifiedTool;scenarios=$scenarios;captured=$false;acceptance='NOT_RUN';ordinaryAppParentRequired=$true;mandatoryManualReview=@('Capture classes enabled, Drop Filtered Events off and stacks captured','Process tree plus PID lifetime','Each candidate write stack including operating-system code inside an application PID','Interactive graph drag/zoom/divider and external viewer tested separately','Real model weights, memory use and throughput remain untested until model prepared')}
    SaveJson (Join-Path $run 'plan.json') $plan
    Write-Output "Prepared only; Procmon and UAC were not invoked. Evidence directory: $run"
    exit 0
}
if(!(Test-Path -LiteralPath (Join-Path $run 'plan.json'))){throw 'Run Prepare first with a new directory.'}
$plan=Get-Content -LiteralPath (Join-Path $run 'plan.json') -Raw|ConvertFrom-Json
if($Mode -eq 'Capture'){
    if(Elevated){throw 'The coordinator must be NON-administrator. Only the observer is elevated; the application inherits the ordinary coordinator token.'}
    if(!$AcceptSysinternalsLicense){throw 'Read the downloaded Eula.txt and explicitly supply -AcceptSysinternalsLicense before using the observer.'}
    if(!$ConfigurationVerified -or !$Configuration -or !(Test-Path -LiteralPath $Configuration -PathType Leaf)){throw 'Supply a reviewed all-events Procmon .PMC and -ConfigurationVerified. See README-procmon.md; a hidden/default profile is not enough evidence.'}
    $configurationPath=InBuild $Configuration
    if((ExistingProcmon).Count -gt 0){throw 'Another Process Monitor instance is active on this session; do not disturb its trace.'}
    foreach($evidence in @('observer-ready.json','observer-failure.json','coordinator-done.json','coordinator-failure.json','cases.json','trace.pml','trace.csv','trace-stacks.xml','observer-complete.json')){
        if(Test-Path -LiteralPath (Join-Path $run $evidence)){throw 'This audit directory already contains an attempted capture. Prepare a fresh run; do not reuse stale ready/completion files or overwrite evidence.'}
    }
    $verifiedTool=VerifyTool
    if($verifiedTool.sha256 -ne $plan.tool.sha256){throw 'Procmon changed since preparation.'}
    $reviewedExe=if($plan.applicationExecutable){$plan.applicationExecutable}else{Join-Path $project 'EnterpriseAI.exe'}
    if((Get-FileHash -LiteralPath $reviewedExe).Hash.ToLowerInvariant() -ne $plan.applicationSha256){throw 'The reviewed application changed; prepare a fresh run.'}
    if(!$plan.applicationFiles){throw 'Prepare a fresh run with the complete EXE/DLL code inventory.'}
    foreach($codeFile in $plan.applicationFiles){if(!(Test-Path -LiteralPath $codeFile.path -PathType Leaf) -or (Get-FileHash -LiteralPath $codeFile.path).Hash.ToLowerInvariant() -ne $codeFile.sha256){throw 'An application/native/runtime DLL changed; prepare a fresh run.'}}
    $hostExe=(Get-Process -Id $PID).Path
    $arguments=@('-NoProfile','-File',(Quote $PSCommandPath),'-Mode','Observer','-SourceRoot',(Quote $source),'-RunDirectory',(Quote $run),'-Configuration',(Quote $configurationPath),'-ConfigurationVerified','-AcceptSysinternalsLicense','-CoordinatorPid',$PID,'-CaptureSeconds',$CaptureSeconds)-join ' '
    # This is the only elevation request. No token manipulation, service creation or policy changes.
    $observer=Start-Process -FilePath $hostExe -Verb RunAs -WindowStyle Hidden -ArgumentList $arguments -PassThru
    $ready=Join-Path $run 'observer-ready.json';$failure=Join-Path $run 'observer-failure.json';$deadline=[DateTime]::UtcNow.AddSeconds(45)
    while(!(Test-Path -LiteralPath $ready)){
        if((Test-Path -LiteralPath $failure) -or $observer.HasExited -or [DateTime]::UtcNow -gt $deadline){throw 'Observer did not become ready; no application scenarios were started.'}
        Start-Sleep -Milliseconds 200
    }
    $cases=@()
    $observerReady=Get-Content -LiteralPath $ready -Raw|ConvertFrom-Json
    $runningApplication=$null
    try {
        foreach($scenario in $plan.scenarios){
            if(!(Get-Process -Id $observerReady.pid -ErrorAction SilentlyContinue)){throw 'Observer capture ended before all application scenarios; do not claim complete lifecycle coverage.'}
            $p=LaunchPipe $scenario.exe @($scenario.arguments) (Join-Path $run 'working')
            $runningApplication=$p
            $row=@{name=$scenario.name;pid=$p.Id;processName=[IO.Path]::GetFileName($scenario.exe);exe=$scenario.exe;arguments=@($scenario.arguments);startedAt=[DateTime]::UtcNow.ToString('o');ordinaryParentPid=$PID;ordinaryParentElevated=$false;kind='application'}
            $cases+=$row;SaveJson (Join-Path $run 'cases.json') $cases
            $stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync()
            if(!$p.WaitForExit([int]$scenario.timeoutSeconds*1000)){$p.Kill($true);$p.WaitForExit();$row.timedOut=$true}
            $row.exitCode=$p.ExitCode;$row.finishedAt=[DateTime]::UtcNow.ToString('o');$row.stdout=$stdout.GetAwaiter().GetResult();$row.stderr=$stderr.GetAwaiter().GetResult()
            $row.expectedOutcome=if($scenario.expectedNonzero){$row.exitCode -ne 0}else{$row.exitCode -eq $scenario.expectedExitCode}
            if($scenario.expectedServiceError){
                try {$payload=$row.stdout|ConvertFrom-Json;$row.observedServiceError=$payload.error.code;$row.expectedServiceError=$scenario.expectedServiceError;$row.expectedOutcome=$row.expectedOutcome -and $payload.error.code -eq $scenario.expectedServiceError}
                catch {$row.expectedOutcome=$false;$row.payloadValidationError=$_.ToString()}
            }
            if($scenario.expectedText){
                try {$payload=$row.stdout|ConvertFrom-Json;$row.expectedText=$scenario.expectedText;$row.observedText=$payload.value.text;$row.expectedOutcome=$row.expectedOutcome -and $payload.value.text -eq $scenario.expectedText}
                catch {$row.expectedOutcome=$false;$row.payloadValidationError=$_.ToString()}
            }
            SaveJson (Join-Path $run 'cases.json') $cases;$p.Dispose();$runningApplication=$null
            if(!(Get-Process -Id $observerReady.pid -ErrorAction SilentlyContinue)){throw 'Observer capture ended during an application scenario; retain the partial evidence and prepare a fresh longer run.'}
        }
    } catch {
        SaveJson (Join-Path $run 'coordinator-failure.json') @{failedAt=[DateTime]::UtcNow.ToString('o');error=$_.ToString()}
        throw
    } finally {
        if($runningApplication){try{if(!$runningApplication.HasExited){$runningApplication.Kill($true);$runningApplication.WaitForExit(5000)|Out-Null};$runningApplication.Dispose()}catch{}}
        SaveJson (Join-Path $run 'coordinator-done.json') @{finishedAt=[DateTime]::UtcNow.ToString('o')}
    }
    $exportDeadline=[DateTime]::UtcNow.AddMinutes(10)
    while(!$observer.WaitForExit(1000)){
        if([DateTime]::UtcNow -gt $exportDeadline){throw 'Observer/export did not finish within ten minutes; retain the PML and inspect observer status.'}
    }
    if(Test-Path -LiteralPath $failure){throw 'Observer failed; inspect observer-failure.json.'}
    & $PSCommandPath -Mode Analyze -SourceRoot $source -RunDirectory $run
    exit 0
}
if($Mode -eq 'Observer'){
    $p=$null
    try {
        if(!(Elevated)){throw 'Observer role requires the explicit Windows UAC elevated instance.'}
        if(!$AcceptSysinternalsLicense -or !$ConfigurationVerified -or $CoordinatorPid -le 0){throw 'Incomplete authorized observer invocation.'}
        $configurationPath=InBuild $Configuration
        if((ExistingProcmon).Count -gt 0){throw 'Do not replace another Procmon session.'}
        VerifyTool|Out-Null
        $trace=Join-Path $run 'trace.pml'
        $p=LaunchPipe $tool @('/AcceptEula','/Quiet','/Minimized','/LoadConfig',$configurationPath,'/NoFilter','/BackingFile',$trace,'/Runtime',"$CaptureSeconds") $run
        $observerPid=$p.Id
        $wait=LaunchPipe $tool @('/AcceptEula','/Quiet','/WaitForIdle') $run
        if(!$wait.WaitForExit(15000)){$wait.Kill();throw 'Procmon WaitForIdle timed out.'};if($wait.ExitCode -ne 0){throw 'Procmon is not ready.'};$wait.Dispose()
        $backingReadyDeadline=[DateTime]::UtcNow.AddSeconds(5)
        while((!(Test-Path -LiteralPath $trace) -or (Get-Item -LiteralPath $trace).Length -eq 0) -and !$p.HasExited -and [DateTime]::UtcNow -lt $backingReadyDeadline){Start-Sleep -Milliseconds 100}
        if($p.HasExited -or !(Test-Path -LiteralPath $trace) -or (Get-Item -LiteralPath $trace).Length -eq 0){throw 'Procmon exited or did not create its backing PML; application scenarios must not start.'}
        SaveJson (Join-Path $run 'observer-ready.json') @{pid=$observerPid;observerShellPid=$PID;elevated=$true;coordinatorPid=$CoordinatorPid;configuration=$configurationPath;configurationSha256=(Get-FileHash -LiteralPath $configurationPath).Hash.ToLowerInvariant();startedAt=[DateTime]::UtcNow.ToString('o')}
        $deadline=[DateTime]::UtcNow.AddSeconds($CaptureSeconds)
        while(!(Test-Path -LiteralPath (Join-Path $run 'coordinator-done.json')) -and !$p.HasExited -and [DateTime]::UtcNow -lt $deadline){if(!(Get-Process -Id $CoordinatorPid -ErrorAction SilentlyContinue)){break};Start-Sleep -Milliseconds 200}
        if(!$p.HasExited){
            $active=ExistingProcmon
            if($active.Count -ne 1 -or $active[0].Id -ne $observerPid){throw 'Procmon ownership became ambiguous; do not terminate somebody else. Own capture has a bounded /Runtime.'}
            $stop=LaunchPipe $tool @('/AcceptEula','/Quiet','/Terminate') $run
            if(!$stop.WaitForExit(15000)){$stop.Kill();throw 'Procmon termination timed out.'};$stop.Dispose()
        }
        if(!$p.WaitForExit(15000)){throw 'Capture did not flush its backing file.'};$p.Dispose()
        if(!(Test-Path -LiteralPath $trace) -or (Get-Item -LiteralPath $trace).Length -eq 0){throw 'No native PML evidence.'}
        foreach($export in @(@{flag='/SaveAs';path=(Join-Path $run 'trace.csv')},@{flag='/SaveAs1';path=(Join-Path $run 'trace-stacks.xml')})){
            $converter=LaunchPipe $tool @('/AcceptEula','/Quiet','/Minimized','/NoConnect','/NoFilter','/OpenLog',$trace,$export.flag,$export.path) $run
            $conversionDeadline=[DateTime]::UtcNow.AddMinutes(10)
            while(!$converter.WaitForExit(1000)){
                if([DateTime]::UtcNow -gt $conversionDeadline){$converter.Kill();throw 'Procmon export timed out after ten minutes; retain PML.'}
            }
            if($converter.ExitCode -ne 0 -or !(Test-Path -LiteralPath $export.path)){throw 'Procmon export failed.'};$converter.Dispose()
        }
        SaveJson (Join-Path $run 'observer-complete.json') @{completedAt=[DateTime]::UtcNow.ToString('o');trace=$trace;traceSha256=(Get-FileHash -LiteralPath $trace).Hash.ToLowerInvariant();rawStacksPreserved=$true;networkSymbolDownloadUsed=$false;observerCategory='observer/tool and kernel driver, not application'}
    } catch {
        SaveJson (Join-Path $run 'observer-failure.json') @{error=$_.ToString();failedAt=[DateTime]::UtcNow.ToString('o')}
        if($p -and !$p.HasExited){
            try{$active=ExistingProcmon;if($active.Count -eq 1 -and $active[0].Id -eq $p.Id){$stop=LaunchPipe $tool @('/AcceptEula','/Quiet','/Terminate') $run;if(!$stop.WaitForExit(5000)){$stop.Kill()};$stop.Dispose()}}catch{}
        }
        throw
    }
    exit 0
}
if($Mode -eq 'Analyze'){
    $csv=Join-Path $run 'trace.csv';$casesPath=Join-Path $run 'cases.json'
    if(!(Test-Path -LiteralPath $csv) -or !(Test-Path -LiteralPath $casesPath)){throw 'Capture CSV and cases.json are required; preparation alone is not an audit.'}
    $cases=@(Get-Content -LiteralPath $casesPath -Raw|ConvertFrom-Json)
    $owned=@{};foreach($case in $cases){$owned[[string]$case.pid]=$case.processName}
    $observerPids=@{}
    $readyFile=Join-Path $run 'observer-ready.json'
    if(Test-Path -LiteralPath $readyFile){$readyData=Get-Content -LiteralPath $readyFile -Raw|ConvertFrom-Json;foreach($observerId in @($readyData.pid,$readyData.observerShellPid,$readyData.coordinatorPid)){if($observerId){$observerPids[[string]$observerId]=$true}}}
    $counts=@{};$candidates=[Collections.Generic.List[object]]::new();$unresolvedIPC=[Collections.Generic.List[object]]::new();$networkCandidates=[Collections.Generic.List[object]]::new();$workerProcesses=[Collections.Generic.List[object]]::new();$workerOwned=@{};$rowNumber=0;$classes=@{}
    Import-Csv -LiteralPath $csv|ForEach-Object {
        $row=$_;$rowNumber++;$pidText=[string]$row.PID;$operation=[string]$row.Operation;$path=[string]$row.Path;$detail=[string]$row.Detail
        $isOwned=$owned.ContainsKey($pidText) -and $owned[$pidText] -eq [string]$row.'Process Name'
        if($isOwned -and $operation -eq 'Process Create' -and $row.Result -eq 'SUCCESS' -and [IO.Path]::GetFileName($path) -ieq 'inference-worker.exe'){
            $childMatch=[regex]::Match($detail,'(?:^|,\s*)PID:\s*(\d+)')
            if($childMatch.Success){$childId=$childMatch.Groups[1].Value;$owned[$childId]='inference-worker.exe';$workerOwned[$childId]=$true;$workerProcesses.Add(@{row=$rowNumber;parentPid=$pidText;pid=$childId;exe=$path;detail=$detail;attribution='Successful Process Create by a recorded application PID; validate full lifetime in PML'})}
        }
        $category=if($isOwned -and $workerOwned.ContainsKey($pidText)){'controlled-model-worker'}elseif($isOwned){'application-and-loaded-DLLs'}elseif($row.'Process Name' -match '^inference-worker\.exe$'){'worker-needs-process-tree-attribution'}elseif($observerPids.ContainsKey($pidText) -or $row.'Process Name' -match '^Procmon(?:64|64a)?\.exe$'){'observer-tool-or-coordinator'}elseif($row.'Process Name' -match '^(System|Registry|svchost\.exe|MsMpEng\.exe|fontdrvhost\.exe|dwm\.exe|WerFault\.exe)$'){'windows-or-security-needs-stack-review'}else{'external-or-unrelated-needs-process-tree-attribution'}
        if(!$counts.ContainsKey($category)){$counts[$category]=0};$counts[$category]++
        if($operation -like 'Reg*'){$classes.registry=$true}elseif($operation -in @('CreateFile','ReadFile','WriteFile','QueryOpen')){$classes.filesystem=$true}elseif($operation -in @('Process Create','Process Start','Process Exit')){$classes.process=$true}
        if($operation -match '^(TCP|UDP) '){$classes.network=$true;if($isOwned){$networkCandidates.Add(@{row=$rowNumber;pid=$pidText;operation=$operation;path=$path;result=$row.Result;detail=$detail;reason='Application network activity requires stack attribution; failed attempts are not silently allowed'})}}
        $write=$operation -match '^(WriteFile|SetEndOfFileInformationFile|SetAllocationInformationFile|SetDispositionInformationFile|SetRenameInformationFile|SetBasicInformationFile|SetSecurityFile|SetEAFile|RegSetValue|RegCreateKey|RegDeleteKey|RegDeleteValue|RegRenameKey|RegSetKeySecurity|RegLoadKey|RegUnloadKey|RegRestoreKey|RegReplaceKey|RegSaveKey)$'
        $reason=if($write){'mutation operation, including unsuccessful attempt'}else{''}
        $accessMatch=[regex]::Match($detail,'(?i)(?:^|,\s*)Desired Access:\s*(.*?)(?=,\s*(?:Disposition|Options|Attributes|ShareMode|AllocationSize|OpenResult|Impersonating|EaLength|SecurityQoS):|$)')
        $desiredAccess=$accessMatch.Groups[1].Value
        if($operation -eq 'CreateFile'){
            # ShareMode: Delete permits OTHER handles to delete; it is not desired delete access.
            $write=$desiredAccess -match '(?i)(?:Generic Write|Write Data|Append Data|Write Attributes|Write EA|Delete|All Access)' -or $detail -match '(?i)Disposition:\s*(Create|OpenIf|Overwrite|OverwriteIf|Supersede)(?:,|$)' -or $detail -match '(?i)Options:.*Delete On Close'
            if($write){$reason='CreateFile requests write access or creating/overwriting disposition'}
        }
        $ipc=$path -match '^(?i)(\\Device\\NamedPipe(?:\\|$)|\\Device\\ConDrv(?:\\|$)|\\\\\.\\pipe(?:\\|$))'
        if($write -and $isOwned -and $ipc){$unresolvedIPC.Add(@{row=$rowNumber;category='unresolved-IPC';pid=$pidText;operation=$operation;path=$path;result=$row.Result;detail=$detail;reason='Confirm this is the recorded anonymous stdout/stderr/worker channel; arbitrary named pipe is not automatically allowed'})}
        if($write -and $isOwned -and !$ipc){$candidates.Add(@{row=$rowNumber;category=$category;pid=$pidText;operation=$operation;path=$path;result=$row.Result;detail=$detail;reason=$reason})}
        if($isOwned -and $operation -eq 'CreateFile' -and $desiredAccess -match '(?i)Maximum Allowed'){$candidates.Add(@{row=$rowNumber;category='unresolved-access';pid=$pidText;operation=$operation;path=$path;result=$row.Result;detail=$detail;reason='maximum access must be reviewed against PML stack'})}
    }
    $indexUnchanged=(Get-FileHash -LiteralPath (Join-Path $project 'db/index.sqlite')).Hash.ToLowerInvariant() -eq $plan.originalIndexSha256
    $summary=@{prepared=$true;captured=$true;automaticAcceptance='REQUIRES_MANUAL_PML_STACK_AND_COMPLETENESS_REVIEW';sourceRowCount=$rowNumber;categoryCounts=$counts;observedClasses=$classes;cases=$cases;candidateApplicationWrites=$candidates.ToArray();candidateCount=$candidates.Count;unresolvedIPC=$unresolvedIPC.ToArray();unresolvedIPCCount=$unresolvedIPC.Count;applicationNetworkCandidates=$networkCandidates.ToArray();applicationNetworkCandidateCount=$networkCandidates.Count;originalIndexUnchanged=$indexUnchanged;stackSource='trace.pml and trace-stacks.xml; CSV itself is not a stack';missingModelWorkerQualification='NOT_TESTED_REAL_WORKER';mandatoryReview=@('Confirm full events, enabled registry/filesystem/process/network classes, no dropped events and first-to-final lifecycle','Match each PID against process image, creation time and parent (PID reuse is possible)','Every candidate including ACCESS DENIED is failure unless raw stack proves independent OS/observer/external origin','Inspect controlled child processes from process tree; unexpected application child is not silently external','Review every unresolved IPC row: actual anonymous stdout/stderr/worker pipe is not arbitrary named-pipe activity','Review application network candidates and failed network attempts against stacks','Inspect unknown mutating FileSystemControl/DeviceIoControl/CreateFileMapping and registry operations','Compare complete docs/db/model inventory using audit_data.py; index hash alone is incomplete','Record interactive graph drag/zoom/divider/source and real worker/cancellation/OOM separately');limitations=@('No claim of zero writes throughout Windows','No clean Windows portability conclusion from this laptop','CSV category labels are provisional, including events in system DLLs inside application PID','This script does not inspect global crash dump policy or modify OS settings')}
    $summary['workerProcesses']=$workerProcesses.ToArray()
    SaveJson (Join-Path $run 'classification.json') $summary
    SaveJson (Join-Path $run 'controlled-worker-processes.json') @{processes=$workerProcesses.ToArray();classification='Provisional parent/create attribution; PID lifetime and loaded images require raw PML review'}
    Write-Output "Captured evidence classified provisionally. Candidate writes: $($candidates.Count). Manual PML/stack review remains mandatory: $run"
    if(!$indexUnchanged){throw 'Original index changed during the audit.'}
}
