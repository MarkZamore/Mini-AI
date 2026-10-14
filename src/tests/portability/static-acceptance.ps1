param(
    [Parameter(Mandatory=$true)][string]$Executable,
    [string]$PythonExecutable=(Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'),
    [switch]$IncludeGraph
)
$ErrorActionPreference='Stop'
$source=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$build=Join-Path $source 'build'
$exe=(Resolve-Path -LiteralPath $Executable).Path
$hash=(Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant()
function Check([bool]$Value,[string]$Message){if(!$Value){throw $Message}}
function Python([string]$Script,[string[]]$Arguments){& $PythonExecutable -B $Script @Arguments;Check ($LASTEXITCODE -eq 0) "Static acceptance failed: $Script"}
Python (Join-Path $PSScriptRoot 'static_portable_transfer.py') @('--exe',$exe)
$transfer=Get-Content -LiteralPath (Join-Path $build 'test-results/static-portable-transfer.json') -Raw|ConvertFrom-Json
$transferredExe=Join-Path $transfer.cases[0].root 'EnterpriseAI.exe'
Python (Join-Path $source 'tests/integration/native_search_parity.py') @('--exe',$transferredExe,'--report-name','static-native-search-parity.json')
Python (Join-Path $PSScriptRoot 'static_source_gate.py') @('--exe',$exe)
Python (Join-Path $PSScriptRoot 'static_shortcut.py') @('--exe',$transferredExe)
& (Join-Path $source 'tests/static-worker/prepare-procmon.ps1') -Executable $exe
Check ($LASTEXITCODE -eq 0) 'Mock worker scenario preparation failed'
$scenarioPath=Join-Path $build 'test-results/static-worker/procmon-scenarios.json'
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
@{success=$true;exe=$exe;exeSha256=$hash;cases=$workerChecks;realModel=$false;limitations=@('Actual standalone MT native mock workers exercise protocols and isolation; no real model weights or model throughput.')}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $build 'test-results/static-main-worker.json') -Encoding utf8
if($IncludeGraph){Python (Join-Path $source 'tests/visual/native_graph_observer.py') @('--exe',$transferredExe,'--output',(Join-Path $build 'test-results/static-native-graph-visual'))}
Check ((Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant() -eq $hash) 'Candidate changed during final acceptance; run again on a frozen artifact'
@{success=$true;exe=$exe;exeSha256=$hash;completedAtUtc=[DateTime]::UtcNow.ToString('o');searchCases=36;workerCases=$workerChecks.Count;graphObserved=[bool]$IncludeGraph;reports=@('static-portable-transfer.json','static-native-search-parity.json','static-source-gate.json','static-shortcut.json','static-main-worker.json');limitations=@('ProcMon and complete original-data integrity audits are separate mandatory gates.','Current Windows laptop; clean Windows and genuine model remain outside current scope.')}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $build 'test-results/static-acceptance.json') -Encoding utf8
Write-Output "PASS frozen static full acceptance SHA256 $hash"
