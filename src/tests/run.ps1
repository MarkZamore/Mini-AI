param([Parameter(Mandatory=$true)][string]$JavaHome,[string]$PythonExecutable=$env:ENTERPRISE_TEST_PYTHON)
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent $PSScriptRoot
$projectRoot=Split-Path -Parent $sourceRoot
Set-Location -LiteralPath $projectRoot
$classes=Join-Path $sourceRoot 'build/classes/java/main'
$tests=Join-Path $sourceRoot 'build/classes/java/test'
$resources=Join-Path $sourceRoot 'build/resources/main'
$libs=(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'build/runtime-libs') -Filter '*.jar').FullName
$classpath=@($tests,$classes,$resources)+$libs -join ';'
$java=Join-Path $JavaHome 'bin/java.exe'
$present=@(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'modules') -Directory | Where-Object {Test-Path -LiteralPath (Join-Path $_.FullName 'module.json')} | ForEach-Object { (Get-Content -LiteralPath (Join-Path $_.FullName 'module.json') -Raw | ConvertFrom-Json).id })
if('inference' -in $present){
    $workerFixtures=Join-Path $PSScriptRoot 'static-worker/build-fixtures.ps1'
    if(!(Test-Path -LiteralPath $workerFixtures)){throw 'Standalone worker fixture builder is missing'}
    & $workerFixtures
    if($LASTEXITCODE -ne 0){throw 'Standalone worker fixture compilation failed'}
}
function Enabled([string]$Directory){$metadata=Join-Path $Directory 'test.json';if(!(Test-Path -LiteralPath $metadata)){return $true};$config=Get-Content -LiteralPath $metadata -Raw|ConvertFrom-Json;if($config.manual){return $false};foreach($id in $config.requiresModules){if($id -notin $present){return $false}};return $true}
function Run([string]$Main,[string[]]$Arguments=@()){
    & $java -XX:-UsePerfData -XX:+DisableAttachMechanism -XX:ErrorFile=NUL --enable-native-access=ALL-UNNAMED -classpath $classpath $Main @Arguments
    if($LASTEXITCODE -ne 0){throw "Test failed: $Main ($LASTEXITCODE)"}
}
function GraphFixture {
    $runtime=Join-Path $sourceRoot 'build/sqlite-tests/root';New-Item -ItemType Directory -Force -Path (Join-Path $runtime 'native')|Out-Null
    foreach($name in @('enterpriseai-platform.dll','enterpriseai-sqlite.dll')){Copy-Item -LiteralPath (Join-Path $sourceRoot ('build/native/'+$name)) -Destination (Join-Path $runtime ('native/'+$name)) -Force}
    if(!(Test-Path -LiteralPath (Join-Path $runtime 'db'))){New-Item -ItemType Junction -Path (Join-Path $runtime 'db') -Target (Join-Path $projectRoot 'db')|Out-Null}
    $output=Join-Path $sourceRoot 'build/test-results/graph-dataset-snapshot.json'
    $start=[Diagnostics.ProcessStartInfo]::new($java);$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($argument in @('-XX:-UsePerfData','-XX:+DisableAttachMechanism','--enable-native-access=ALL-UNNAMED',('-Denterprise.root='+$runtime),'-classpath',$classpath,'ai.enterprise.bootstrap.Main','--graph')){$start.ArgumentList.Add($argument)}
    $child=[Diagnostics.Process]::Start($start);$snapshot=$child.StandardOutput.ReadToEnd();$errorText=$child.StandardError.ReadToEnd();$child.WaitForExit()
    if($child.ExitCode -ne 0 -or $errorText){throw ('Read-only graph fixture export failed: '+$errorText+$snapshot)}
    [IO.File]::WriteAllText($output,$snapshot,[Text.UTF8Encoding]::new($false));return $output
}
foreach($folder in Get-ChildItem -LiteralPath $PSScriptRoot -Directory){
    if(!(Enabled $folder.FullName)){continue}
    if($folder.Name -in @('integration','platform','module-removal','visual','portability')){continue}
    foreach($file in Get-ChildItem -LiteralPath $folder.FullName -Recurse -Filter '*Test.java'){
        $match=[regex]::Match([IO.File]::ReadAllText($file.FullName),'package\s+([A-Za-z0-9_.]+)\s*;')
        $main=if($match.Success){$match.Groups[1].Value+'.'+$file.BaseName}else{$file.BaseName}
        Run $main @($sourceRoot)
    }
}
foreach($module in Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'modules') -Directory){
    $folder=Join-Path $module.FullName 'tests'
    if(!(Test-Path -LiteralPath (Join-Path $module.FullName 'module.json')) -or !(Test-Path -LiteralPath $folder) -or !(Enabled $folder)){continue}
    foreach($file in Get-ChildItem -LiteralPath $folder -Recurse -Filter '*Test.java'){
        $testMetadata=Join-Path $folder ($file.BaseName+'.test.json');$testArguments=@((Join-Path $sourceRoot ('build/test-results/module-fixtures/'+$module.Name+'/'+$file.BaseName)))
        if(Test-Path -LiteralPath $testMetadata){
            $config=Get-Content -LiteralPath $testMetadata -Raw|ConvertFrom-Json
            if(@($config.requiresModules|Where-Object {$_ -notin $present}).Count){continue}
            if($config.nativeUi){$testArguments=@((Join-Path $sourceRoot 'build/native'))}
            if($config.fixture -eq 'graph-snapshot' -and !@($config.fixtureRequiresModules|Where-Object {$_ -and $_ -notin $present}).Count){
                $fixture=GraphFixture
                $testArguments=if($config.nativeUi){@((Join-Path $sourceRoot 'build/native'),$fixture,(Join-Path $sourceRoot 'build/test-results/graph-canvas-dataset.json'))}else{@($fixture,(Join-Path $sourceRoot 'build/test-results/graph-physics-dataset.json'))}
            }
        }
        $match=[regex]::Match([IO.File]::ReadAllText($file.FullName),'package\s+([A-Za-z0-9_.]+)\s*;')
        $main=if($match.Success){$match.Groups[1].Value+'.'+$file.BaseName}else{$file.BaseName}
        Run $main $testArguments
    }
}
& (Join-Path $PSScriptRoot 'platform/prepare-fixture.ps1') -SourceRoot $sourceRoot
Run 'ai.enterprise.tests.NativePlatformTest' @($sourceRoot)
if(('database' -in $present) -and ('search' -in $present)){
    $python=$PythonExecutable
    if(!$python){$command=Get-Command python -ErrorAction SilentlyContinue;if($command){$python=$command.Source}}
    if(!$python -or !(Test-Path -LiteralPath $python)){throw 'Provide -PythonExecutable for the developer-only reference baseline (Python 3.12 with Unicode 15 and SQLite 3.53.1). The application does not require Python.'}
    $version=& $python -B -c 'import sqlite3,unicodedata; print(sqlite3.sqlite_version,unicodedata.unidata_version)'
    if($LASTEXITCODE -ne 0 -or $version.Trim() -ne '3.53.1 15.0.0'){throw 'Developer reference requires SQLite 3.53.1 and Unicode 15.0.0; select the matching interpreter with -PythonExecutable.'}
    & $python -B (Join-Path $PSScriptRoot 'integration/search_parity_baseline.py');if($LASTEXITCODE -ne 0){throw 'Read-only parity baseline failed'}
    $runtime=Join-Path $sourceRoot 'build/sqlite-tests/root';New-Item -ItemType Directory -Force -Path (Join-Path $runtime 'native')|Out-Null
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'build/native/enterpriseai-sqlite.dll') -Destination (Join-Path $runtime 'native/enterpriseai-sqlite.dll') -Force
    if(!(Test-Path -LiteralPath (Join-Path $runtime 'db'))){New-Item -ItemType Junction -Path (Join-Path $runtime 'db') -Target (Join-Path $projectRoot 'db')|Out-Null}
    Run 'ai.enterprise.tests.SearchParityTest' @($projectRoot,$runtime)
}
Write-Output 'All selected developer tests passed.'
