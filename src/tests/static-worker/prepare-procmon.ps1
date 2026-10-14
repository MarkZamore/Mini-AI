param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fixtures=Join-Path $sourceRoot 'build/test-results/static-worker'
$main=(Resolve-Path -LiteralPath $Executable).Path
if([IO.Path]::GetExtension($main) -ne '.exe'){throw 'Expected verified standalone application EXE'}
$scenarios=@()
foreach($name in @('weak','medium','powerful')){
  $root=Join-Path $fixtures ("packages/"+$name)
  $dest=Join-Path $root 'EnterpriseAI.exe'
  Copy-Item -LiteralPath $main -Destination $dest -Force
  foreach($mode in @('ordinary','fixture-error','fixture-crash','fixture-hang')){
    $argv=@('--command','model-generate',$mode)
    $scenario=@{name=('static-worker-'+$name+'-'+$mode);exe=$dest;arguments=$argv;timeoutSeconds=20;expectedExitCode=0}
    switch($mode){
      'fixture-error'{$scenario.expectedServiceError='OUT_OF_MEMORY'}
      'fixture-crash'{$scenario.expectedServiceError='WORKER_EXITED'}
      'fixture-hang'{$scenario.arguments=$argv+@('1000');$scenario.expectedServiceError='CANCELLED'}
      default{$scenario.expectedText=('Variant'+(@('weak','medium','powerful').IndexOf($name)+1))}
    }
    $scenarios+=,$scenario
  }
}
$report=Join-Path $fixtures 'procmon-scenarios.json'
[IO.File]::WriteAllText($report,($scenarios|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
Write-Output $report
