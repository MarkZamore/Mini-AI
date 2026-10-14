param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$projectRoot=Split-Path -Parent $sourceRoot
$probeRoot=Join-Path $sourceRoot 'build/test-results/fatal-probe'
$classes=Join-Path $probeRoot 'classes'
$observer=Join-Path $sourceRoot 'build/test-results/fatal-probe-report.json'
New-Item -ItemType Directory -Force -Path $probeRoot,$classes,(Join-Path $probeRoot 'working'),(Join-Path $probeRoot 'temporary')|Out-Null
$env:TEMP=Join-Path $probeRoot 'temporary';$env:TMP=$env:TEMP
$vs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Tools/MSVC')}} | Select-Object -First 1
$msvc=Get-ChildItem (Join-Path $vs.FullName 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$kits='C:/Program Files (x86)/Windows Kits/10'
$sdk=Get-ChildItem (Join-Path $kits 'Include') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'um/windows.h')} | Sort-Object Name -Descending | Select-Object -First 1
$env:INCLUDE=@((Join-Path $msvc.FullName 'include'),(Join-Path $sdk.FullName 'ucrt'),(Join-Path $sdk.FullName 'um'),(Join-Path $sdk.FullName 'shared'))-join ';'
$env:LIB=@((Join-Path $msvc.FullName 'lib/x64'),(Join-Path $kits "Lib/$($sdk.Name)/ucrt/x64"),(Join-Path $kits "Lib/$($sdk.Name)/um/x64"))-join ';'
$env:PATH=(Join-Path $msvc.FullName 'bin/Hostx64/x64')+';'+$env:PATH
$helper=Join-Path $sourceRoot 'build/build-logic-classes'
$libraries=(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'build/runtime-libs') -Filter '*.jar'|Where-Object {$_.Name -match '^(nativeimage|word)-'}).FullName
$classpath=@($helper,$classes)+$libraries -join ';'
& (Join-Path $JavaHome 'bin/javac.exe') -d $classes (Join-Path $PSScriptRoot 'CrashProbe.java');if($LASTEXITCODE -ne 0){throw 'Probe Java compilation failed'}
Push-Location -LiteralPath $probeRoot
try {
    & cl.exe /nologo /LD /O2 /MT ("/I$JavaHome/include") ("/I$JavaHome/include/win32") (Join-Path $PSScriptRoot 'fatal_probe.c') ("/Fo$probeRoot/fatal_probe.obj") /link ("/OUT:$probeRoot/fatal-probe.dll") ("/IMPLIB:$probeRoot/fatal-probe.lib");if($LASTEXITCODE -ne 0){throw 'Probe native compilation failed'}
}finally{Pop-Location}
$options=@('-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.windows.headers=ALL-UNNAMED','--no-fallback','--enable-native-access=ALL-UNNAMED','-O1','-march=x86-64','-R:MaxHeapSize=33554432','--parallelism=2','--features=ai.enterprise.build.NoFileLogFeature','--initialize-at-build-time=ai.enterprise.build.NoFileLogHandler','--class-path',$classpath,'-o',(Join-Path $probeRoot 'CrashProbe'),'-J-Xmx2g','-J-XX:-UsePerfData','CrashProbe')
& (Join-Path $JavaHome 'bin/native-image.cmd') @options;if($LASTEXITCODE -ne 0){throw 'Probe Native Image compilation failed'}
function Snapshot {
    $roots=@($probeRoot,(Join-Path $sourceRoot 'build/tmp'),(Join-Path $env:LOCALAPPDATA 'CrashDumps'),[IO.Path]::GetTempPath()) | Select-Object -Unique
    $paths=@{}
    foreach($folder in $roots){if(Test-Path -LiteralPath $folder){foreach($file in Get-ChildItem -LiteralPath $folder -File -Recurse -ErrorAction SilentlyContinue){$paths[$file.FullName]=@($file.Length,$file.LastWriteTimeUtc.Ticks)}}}
    foreach($file in Get-ChildItem -LiteralPath $projectRoot -File){if($file.Name -match '(hs_err|svm_err|\.hprof$|\.dmp$)'){$paths[$file.FullName]=@($file.Length,$file.LastWriteTimeUtc.Ticks)}}
    return $paths
}
$before=Snapshot
$results=@()
foreach($mode in @('fatal','oom')){
    $start=[Diagnostics.ProcessStartInfo]::new((Join-Path $probeRoot 'CrashProbe.exe'))
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.WorkingDirectory=Join-Path $probeRoot 'working';$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.ArgumentList.Add((Join-Path $probeRoot 'fatal-probe.dll'));$start.ArgumentList.Add($mode)
    $process=[Diagnostics.Process]::Start($start);$stdout=$process.StandardOutput.ReadToEnd();$stderr=$process.StandardError.ReadToEnd();$process.WaitForExit();$exitCode=$process.ExitCode
    if(($mode -eq 'fatal' -and $exitCode -eq 0) -or ($mode -eq 'oom' -and $exitCode -ne 0)){throw "Unexpected $mode exit code $exitCode"}
    $results+=@{mode=$mode;exitCode=$exitCode;stdout=$stdout;stderr=$stderr}
}
$after=Snapshot
$changes=@($after.Keys|Where-Object {!$before.ContainsKey($_) -or (($after[$_]-join ',') -ne ($before[$_]-join ','))})
$owned=@($changes|Where-Object {$_.StartsWith($probeRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)})
$report=@{completed=($owned.Count -eq 0);testedAt=[DateTime]::UtcNow.ToString('o');scope='Developer-only Native Image using the production NoFileLogFeature and NoFileLogHandler, JNI FatalError and caught Java OOM';cases=$results;changedFiles=$changes;ownedProbeChanges=$owned;limitations=@('Not a Process Monitor trace or registry audit','Windows WER/security/other process operations are outside application guarantee','Snapshots can miss transient operations; clean-Windows ProcMon remains required')}
$report|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $observer -Encoding UTF8
if($owned.Count -ne 0){throw 'Fatal/OOM probe created or modified files; inspect observer report'}
Write-Output 'Fatal JNI / Java OOM probe passed: no owned probe files or dumps created.'
