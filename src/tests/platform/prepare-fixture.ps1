param([string]$SourceRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop'
$sourceRootPath=(Resolve-Path -LiteralPath $SourceRoot).Path
$fixtureRoot=Join-Path $sourceRootPath 'build/test-results/platform-fixtures'
New-Item -ItemType Directory -Force -Path $fixtureRoot,(Join-Path $fixtureRoot 'native') | Out-Null
$env:TEMP=$fixtureRoot
$env:TMP=$fixtureRoot
$vs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'VC/Tools/MSVC') } } | Select-Object -First 1
if(!$vs){throw 'MSVC is required to prepare the native worker test fixture.'}
$msvc=Get-ChildItem (Join-Path $vs.FullName 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$kits='C:/Program Files (x86)/Windows Kits/10'
$sdk=Get-ChildItem (Join-Path $kits 'Include') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'um/windows.h') } | Sort-Object Name -Descending | Select-Object -First 1
if(!$sdk){throw 'Windows SDK is required to prepare the native worker test fixture.'}
$env:INCLUDE=@((Join-Path $msvc.FullName 'include'),(Join-Path $sdk.FullName 'ucrt'),(Join-Path $sdk.FullName 'um'),(Join-Path $sdk.FullName 'shared')) -join ';'
$env:LIB=@((Join-Path $msvc.FullName 'lib/x64'),(Join-Path $kits "Lib/$($sdk.Name)/ucrt/x64"),(Join-Path $kits "Lib/$($sdk.Name)/um/x64")) -join ';'
$env:PATH=(Join-Path $msvc.FullName 'bin/Hostx64/x64')+';'+(Join-Path $kits "bin/$($sdk.Name)/x64")+';'+$env:PATH
$compiler=Join-Path $msvc.FullName 'bin/Hostx64/x64/cl.exe'
$arguments=@('/nologo','/utf-8','/O2','/MT',(Join-Path $PSScriptRoot 'worker_fixture.c'),('/Fo'+(Join-Path $fixtureRoot 'worker_fixture.obj')),('/Fe'+(Join-Path $fixtureRoot 'worker-fixture.exe')),'/link','/SUBSYSTEM:CONSOLE','/MANIFEST:EMBED','/MANIFESTUAC:NO')
Push-Location -LiteralPath $fixtureRoot
try { & $compiler @arguments; if($LASTEXITCODE -ne 0){throw "Fixture compiler failed: $LASTEXITCODE"} } finally { Pop-Location }
$platform=Join-Path $sourceRootPath 'build/native/enterpriseai-platform.dll'
if(!(Test-Path -LiteralPath $platform)){throw 'Build the platform native library before this fixture.'}
Copy-Item -LiteralPath $platform -Destination (Join-Path $fixtureRoot 'native/enterpriseai-platform.dll') -Force
Write-Output $fixtureRoot
