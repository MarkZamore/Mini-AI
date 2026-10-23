param([string]$ArtifactRoot=(Join-Path $PSScriptRoot '../build/research-20261024/gpu-worker'),[switch]$EnableEvidence)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$ArtifactRoot=[IO.Path]::GetFullPath($ArtifactRoot)
New-Item -ItemType Directory -Force -Path $ArtifactRoot,"$ArtifactRoot/downloads","$ArtifactRoot/vendor","$ArtifactRoot/import-libs" | Out-Null
$assets=@(
  @{name='cudart-llama-bin-win-cuda-12.4-x64.zip';size=391443627;digest='sha256:8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6'},
  @{name='llama-b11669-bin-win-cuda-12.4-x64.zip';size=266582698;digest='sha256:c2da1f73ed176ae093ac5aceb978a7b167a16113f6d74c6789eeaee2135cb8dc'})
foreach($a in $assets){$a.browser_download_url='https://github.com/ggml-org/llama.cpp/releases/download/b11669/'+$a.name}
$assets | ConvertTo-Json | Set-Content -LiteralPath "$ArtifactRoot/release-assets.json" -Encoding utf8
$python=Join-Path $PSScriptRoot '../build/model-preparation/venv/Scripts/python.exe'
if(!(Test-Path -LiteralPath $python)){throw 'Provide the existing preparation Python environment; no installation is performed.'}
& $python (Join-Path $PSScriptRoot 'fetch_release.py') $ArtifactRoot
if($LASTEXITCODE){throw 'Pinned release download failed'}
$commit='620d2225cc436ab0b001e3200e590e3b286ef9ab'
$sourceZip="$ArtifactRoot/downloads/llama-source-620d2225.zip"
if(!(Test-Path -LiteralPath $sourceZip)){Invoke-WebRequest -Uri "https://codeload.github.com/ggml-org/llama.cpp/zip/$commit" -OutFile $sourceZip}
if((Get-FileHash -LiteralPath $sourceZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne '48b431f8984dcc79efd2589de85fc53e42c6193522c97641e8dbc4376ebb3a5a'){throw 'Pinned source archive SHA-256 mismatch'}
$engine="$ArtifactRoot/vendor/llama.cpp-$commit"
if(!(Test-Path -LiteralPath "$engine/include/llama.h")){Expand-Archive -LiteralPath $sourceZip -DestinationPath "$ArtifactRoot/vendor"}
foreach($pair in @(@('llama-b11669-bin-win-cuda-12.4-x64.zip','bin-cuda12'),@('cudart-llama-bin-win-cuda-12.4-x64.zip','cudart12'))){
  if(!(Test-Path -LiteralPath "$ArtifactRoot/vendor/$($pair[1])")){Expand-Archive -LiteralPath "$ArtifactRoot/downloads/$($pair[0])" -DestinationPath "$ArtifactRoot/vendor/$($pair[1])"}
}
$vs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Tools/MSVC')}} | Select-Object -First 1
if(!$vs){throw 'Existing MSVC required to build the wrapper'}
$msvc=Get-ChildItem (Join-Path $vs.FullName 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$kits='C:/Program Files (x86)/Windows Kits/10'
$sdk=Get-ChildItem (Join-Path $kits 'Include') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'um/windows.h')} | Sort-Object Name -Descending | Select-Object -First 1
$env:PATH=(Join-Path $msvc.FullName 'bin/Hostx64/x64')+';'+(Join-Path $kits "bin/$($sdk.Name)/x64")+';'+$env:PATH
$env:INCLUDE=@((Join-Path $msvc.FullName 'include'),(Join-Path $sdk.FullName 'ucrt'),(Join-Path $sdk.FullName 'um'),(Join-Path $sdk.FullName 'shared')) -join ';'
$env:LIB=@((Join-Path $msvc.FullName 'lib/x64'),(Join-Path $kits "Lib/$($sdk.Name)/ucrt/x64"),(Join-Path $kits "Lib/$($sdk.Name)/um/x64")) -join ';'
foreach($name in @('llama','ggml','ggml-base')){
  $dll=Get-ChildItem -LiteralPath "$ArtifactRoot/vendor/bin-cuda12" -Recurse -File -Filter "$name.dll" | Select-Object -First 1
  $exports=& dumpbin.exe /nologo /exports $dll.FullName
  $symbols=@($exports | ForEach-Object {if($_ -match '^\s+\d+\s+[0-9A-F]+\s+[0-9A-F]+\s+(\S+)'){$Matches[1]}})
  if(!$symbols.Count){throw "No exports: $name"}
  @("LIBRARY $name.dll",'EXPORTS')+$symbols | Set-Content -LiteralPath "$ArtifactRoot/import-libs/$name.def" -Encoding ascii
  & lib.exe /nologo /machine:x64 "/def:$ArtifactRoot/import-libs/$name.def" "/out:$ArtifactRoot/import-libs/$name.lib"
  if($LASTEXITCODE){throw "Import library failed: $name"}
}
$cmake=Join-Path $PSScriptRoot '../build/model-preparation/venv/Lib/site-packages/cmake/data/bin/cmake.exe'
if(!(Test-Path -LiteralPath $cmake)){throw 'Existing CMake 3.28+ required'}
$evidence=if($EnableEvidence){'ON'}else{'OFF'}
if($EnableEvidence){
  $third=Join-Path $PSScriptRoot '../build/research-20261024/dependencies/sentencepiece-0.2.2/third_party'
  if(!(Test-Path -LiteralPath "$third/absl") -and (Test-Path -LiteralPath "$third/abseil-cpp/absl")){
    # Upstream uses a symlink; a real copy supports ordinary Windows user accounts.
    Copy-Item -LiteralPath "$third/abseil-cpp/absl" -Destination "$third/absl" -Recurse
  }
}
& $cmake -S $PSScriptRoot -B "$ArtifactRoot/build" -G 'NMake Makefiles' '-DCMAKE_BUILD_TYPE=Release' "-DENGINE_SOURCE=$engine" "-DENGINE_LIBS=$ArtifactRoot/import-libs" "-DWORKER_ENABLE_EVIDENCE=$evidence"
if($LASTEXITCODE){throw 'Worker configure failed'}
& $cmake --build "$ArtifactRoot/build" --parallel 4
if($LASTEXITCODE){throw 'Worker build failed'}
Write-Output "Built: $ArtifactRoot/build/inference-worker.exe"
