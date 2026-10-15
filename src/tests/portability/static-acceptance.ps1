param(
    [Parameter(Mandatory=$true)][string]$Executable,
    [string]$PythonExecutable=(Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'),
    [switch]$IncludeGraph,
    [string]$ReportPrefix="dusty-yellow"
)
$ErrorActionPreference='Stop'
$source=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$build=Join-Path $source 'build'
$exe=(Resolve-Path -LiteralPath $Executable).Path
if($ReportPrefix -notmatch '^[a-zA-Z0-9-]+$'){throw 'Invalid report prefix'}
$hash=(Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant()
function Check([bool]$Value,[string]$Message){if(!$Value){throw $Message}}
function Python([string]$Script,[string[]]$Arguments){& $PythonExecutable -B $Script @Arguments;Check ($LASTEXITCODE -eq 0) "Static acceptance failed: $Script"}
Python (Join-Path $PSScriptRoot 'static_portable_transfer.py') @('--exe',$exe,'--report-prefix',$ReportPrefix)
$transfer=Get-Content -LiteralPath (Join-Path $build ('test-results/'+$ReportPrefix+'-portable-transfer.json')) -Raw|ConvertFrom-Json
$transferredExe=Join-Path $transfer.cases[0].root ([IO.Path]::GetFileName($exe))
Python (Join-Path $source 'tests/integration/native_search_parity.py') @('--exe',$transferredExe,'--report-name',($ReportPrefix+'-native-search-parity.json'))
Python (Join-Path $PSScriptRoot 'static_source_gate.py') @('--exe',$exe,'--report-prefix',$ReportPrefix)
Python (Join-Path $PSScriptRoot 'static_shortcut.py') @('--exe',$transferredExe,'--report-prefix',$ReportPrefix)
& (Join-Path $source 'tests/static-worker/prepare-scenarios.ps1') -Executable $exe -ReportPrefix $ReportPrefix
Check ($LASTEXITCODE -eq 0) 'Mock worker scenario preparation failed'
$scenarioPath=Join-Path $build ('test-results/'+$ReportPrefix+'-worker/worker-scenarios.json')
$workerChecks=@()
foreach($scenario in (Get-Content -LiteralPath $scenarioPath -Raw|ConvertFrom-Json)){
    $start=[Diagnostics.ProcessStartInfo]::new($scenario.exe);$start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.WorkingDirectory=$env:SystemRoot;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.StandardOutputEncoding=[Text.Encoding]::UTF8;$start.StandardErrorEncoding=[Text.Encoding]::UTF8
    foreach($argument in $scenario.arguments){$start.ArgumentList.Add($argument)}
    $child=[Diagnostics.Process]::Start($start);$out=$child.StandardOutput.ReadToEndAsync();$err=$child.StandardError.ReadToEndAsync()
    try{
        if(!$child.WaitForExit(25000)){$child.Kill($true);throw "Worker lifecycle timed out: $($scenario.name)"}
        $stdout=$out.GetAwaiter().GetResult();$stderr=$err.GetAwaiter().GetResult()
        Check ($child.ExitCode -eq $scenario.expectedExitCode -and !$stderr) "Worker lifecycle exit failed: $($scenario.name): $stdout $stderr"
        $payload=$stdout|ConvertFrom-Json
        if($scenario.expectedServiceError){Check ($payload.error.code -eq $scenario.expectedServiceError) "Wrong worker error: $($scenario.name): $stdout"}
        if($scenario.expectedText){Check ($payload.success -and $payload.value.text -eq $scenario.expectedText) "Wrong model variant text: $($scenario.name): $stdout"}
        $workerChecks+=@{name=$scenario.name;passed=$true;result=$payload;exitCode=$child.ExitCode}
    } finally{$child.Dispose()}
}
@{success=$true;exe=$exe;exeSha256=$hash;cases=$workerChecks;realModel=$false;limitations=@('Actual standalone MT native mock workers exercise protocols and isolation; no real model weights or model throughput.')}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $build ('test-results/'+$ReportPrefix+'-main-worker.json')) -Encoding utf8
Python (Join-Path $PSScriptRoot 'native_branding_observer.py') @('--exe',$transferredExe,'--output',(Join-Path $build ('test-results/'+$ReportPrefix+'-branding-ui.json')))
Python (Join-Path $PSScriptRoot 'native_assistant_observer.py') @('--exe',$transferredExe,'--scenario','missing-model','--output',(Join-Path $build ('test-results/'+$ReportPrefix+'-assistant-missing-model.json')))
$assistantFixture=Join-Path $build ('test-results/'+$ReportPrefix+'-worker/packages/weak')
New-Item -ItemType Directory -Force -Path (Join-Path $assistantFixture 'db')|Out-Null
Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $source) 'db/index.sqlite') -Destination (Join-Path $assistantFixture 'db/index.sqlite')
Python (Join-Path $PSScriptRoot 'native_assistant_observer.py') @('--exe',(Join-Path $assistantFixture ([IO.Path]::GetFileName($exe))),'--scenario','citation','--output',(Join-Path $build ('test-results/'+$ReportPrefix+'-assistant-citation.json')))
$stopBuild=Join-Path $build ('test-results/'+$ReportPrefix+'-stop-worker-build')
& (Join-Path $PSScriptRoot 'prepare-stop-worker.ps1') -FixtureRoot $stopBuild
Check ($LASTEXITCODE -eq 0) 'Static hanging stop fixture preparation failed'
$stopFixture=Join-Path $build ('test-results/'+$ReportPrefix+'-assistant-stop-package')
New-Item -ItemType Directory -Force -Path (Join-Path $stopFixture 'db')|Out-Null
Copy-Item -LiteralPath $exe -Destination (Join-Path $stopFixture ([IO.Path]::GetFileName($exe)))
Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $source) 'db/index.sqlite') -Destination (Join-Path $stopFixture 'db/index.sqlite')
Copy-Item -LiteralPath (Join-Path $stopBuild 'build/test-results/static-worker/packages/weak/model') -Destination (Join-Path $stopFixture 'model') -Recurse
Python (Join-Path $PSScriptRoot 'native_assistant_observer.py') @('--exe',(Join-Path $stopFixture ([IO.Path]::GetFileName($exe))),'--scenario','stop-cancel','--output',(Join-Path $build ('test-results/'+$ReportPrefix+'-assistant-stop-cancel.json')))
if($IncludeGraph){Python (Join-Path $source 'tests/visual/native_graph_observer.py') @('--exe',$transferredExe,'--output',(Join-Path $build ('test-results/'+$ReportPrefix+'-native-graph-visual')))}
Check ((Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant() -eq $hash) 'Candidate changed during final acceptance; run again on a frozen artifact'
@{success=$true;exe=$exe;exeSha256=$hash;completedAtUtc=[DateTime]::UtcNow.ToString('o');searchCases=36;workerCases=$workerChecks.Count;graphObserved=[bool]$IncludeGraph;reports=@(($ReportPrefix+'-portable-transfer.json'),($ReportPrefix+'-native-search-parity.json'),($ReportPrefix+'-source-gate.json'),($ReportPrefix+'-shortcut.json'),($ReportPrefix+'-main-worker.json'),($ReportPrefix+'-branding-ui.json'),($ReportPrefix+'-assistant-missing-model.json'),($ReportPrefix+'-assistant-citation.json'),($ReportPrefix+'-assistant-stop-cancel.json'));limitations=@('Original-data integrity is a separate mandatory gate. Process Monitor is excluded from the release pipeline by user instruction; absence of transient writes and registry changes is not verified.','Current Windows laptop; clean Windows and genuine model remain outside current scope.')}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $build ('test-results/'+$ReportPrefix+'-acceptance.json')) -Encoding utf8
Write-Output "PASS frozen static full acceptance SHA256 $hash"
