param([string]$SourceRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop'
$build = Join-Path $SourceRoot 'build/test-results/static-worker'
New-Item -ItemType Directory -Force -Path $build | Out-Null
$vs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Tools/MSVC')} } | Select-Object -First 1
if(!$vs){throw 'MSVC is required to build test-only native workers.'}
$msvc=Get-ChildItem (Join-Path $vs.FullName 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$kits='C:/Program Files (x86)/Windows Kits/10'
$sdk=Get-ChildItem (Join-Path $kits 'Include') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'um/windows.h')} | Sort-Object Name -Descending | Select-Object -First 1
$env:INCLUDE=@((Join-Path $msvc.FullName 'include'),(Join-Path $sdk.FullName 'ucrt'),(Join-Path $sdk.FullName 'um'),(Join-Path $sdk.FullName 'shared')) -join ';'
$env:LIB=@((Join-Path $msvc.FullName 'lib/x64'),(Join-Path $kits "Lib/$($sdk.Name)/ucrt/x64"),(Join-Path $kits "Lib/$($sdk.Name)/um/x64")) -join ';'
$env:PATH=(Join-Path $kits ("bin/"+$sdk.Name+"/x64"))+';'+(Join-Path $msvc.FullName 'bin/Hostx64/x64')+';'+$env:PATH
$compiler=Join-Path $msvc.FullName 'bin/Hostx64/x64/cl.exe'
$packages=@(@{name='weak';variant=1;file='test.gguf';magic='GGUFfixture'},@{name='medium';variant=2;file='test.safetensors';magic='SAFEfixture'},@{name='powerful';variant=3;file='test.bin';magic='BINfixture'})
foreach($package in $packages){
  $model=Join-Path $build ("packages/{0}/model" -f $package.name)
  New-Item -ItemType Directory -Force -Path (Join-Path $model 'weights') | Out-Null
  $exe=Join-Path $model 'inference-worker.exe'
  & $compiler /nologo /O2 /MT /Brepro ("/DVARIANT="+$package.variant) (Join-Path $PSScriptRoot 'worker.c') ("/Fo"+(Join-Path $build ($package.name+'.obj'))) ("/Fe"+$exe) /link /INCREMENTAL:NO /SUBSYSTEM:CONSOLE /MANIFEST:EMBED ("/MANIFESTINPUT:"+(Join-Path $PSScriptRoot 'worker.manifest'))
  if($LASTEXITCODE -ne 0){throw 'Test worker compilation failed'}
  $weight=Join-Path $model ("weights/"+$package.file)
  [IO.File]::WriteAllBytes($weight,[Text.Encoding]::ASCII.GetBytes($package.magic))
  $files=@(@{path='inference-worker.exe';sha256=(Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant()},@{path=('weights/'+$package.file);sha256=(Get-FileHash -LiteralPath $weight).Hash.ToLowerInvariant()})
  $manifest=@{schemaVersion=1;protocol='enterpriseai.inference.v1';executable='inference-worker.exe';engine=('fixture-engine-'+$package.name);revision=('fixture-revision-'+$package.variant);architecture='windows-x64';supportedIsa=@('x86-64');weights=$files[1];files=$files;precision='test-only';contextTokens=8192;threads=1;minFreeMemoryBytes=67108864;maxProcessMemoryBytes=33554432}
  [IO.File]::WriteAllText((Join-Path $model 'worker.json'),($manifest|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
}
Write-Output "Three standalone test packages: $build/packages"
