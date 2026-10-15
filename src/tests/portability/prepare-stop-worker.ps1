param([Parameter(Mandatory=$true)][string]$FixtureRoot)
$ErrorActionPreference='Stop'
$source=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$build=[IO.Path]::GetFullPath((Join-Path $source 'build'))
$target=[IO.Path]::GetFullPath($FixtureRoot)
if(!$target.StartsWith($build+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Stop fixture must remain within src/build'}
if(Test-Path -LiteralPath $target){throw 'Refusing to overwrite an existing stop fixture'}
$copied=Join-Path $target 'tests/static-worker'
New-Item -ItemType Directory -Force -Path $copied|Out-Null
foreach($name in @('worker.c','worker.manifest','build-fixtures.ps1')){Copy-Item -LiteralPath (Join-Path $source ('tests/static-worker/'+$name)) -Destination (Join-Path $copied $name)}
$worker=Join-Path $copied 'worker.c'
$text=[IO.File]::ReadAllText($worker)
$old='if (strstr(line,"fixture-hang")) Sleep(INFINITE);'
if(!$text.Contains($old)){throw 'Reviewed mock hang call changed'}
# Only the build-local copy is modified. Every generate request emits its token
# and blocks indefinitely, deliberately ignoring cancel so the Job Object deadline is exercised.
[IO.File]::WriteAllText($worker,$text.Replace($old,'Sleep(INFINITE);'),[Text.UTF8Encoding]::new($false))
& (Join-Path $copied 'build-fixtures.ps1') -SourceRoot $target
if($LASTEXITCODE -ne 0){throw 'Standalone MT stop fixture compilation failed'}
@{sourceWorkerSha256=(Get-FileHash -LiteralPath (Join-Path $source 'tests/static-worker/worker.c')).Hash.ToLowerInvariant();buildLocalWorkerSha256=(Get-FileHash -LiteralPath $worker).Hash.ToLowerInvariant();modification='Replace conditional fixture-hang with unconditional Sleep(INFINITE) after token';runtime='Static /MT standalone test-only worker';modelPath=(Join-Path $target 'build/test-results/static-worker/packages/weak/model')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $target 'preparation.json') -Encoding utf8
