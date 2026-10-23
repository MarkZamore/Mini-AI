param([string]$ArtifactRoot=(Join-Path $PSScriptRoot '../build/research-20261024/gpu-worker'),
      [string]$Destination='', [ValidateSet('cuda','cpu')][string]$Backend='cuda',
      [int]$ContextTokens=16384,[int]$Threads=12,[long]$GpuBudgetBytes=8589934592,[long]$GpuReserveBytes=3221225472,
      [long]$MaxProcessMemoryBytes=8589934592,[long]$MinFreeMemoryBytes=9663676416,
      [string]$WeightsPath='', [string]$Precision='Q6_K',[switch]$EnableEvidence,[string]$ModelLicensePath='')
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$ArtifactRoot=[IO.Path]::GetFullPath($ArtifactRoot)
if(!$Destination){$Destination=Join-Path $ArtifactRoot 'package/model'}
$Destination=[IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $Destination,"$Destination/runtime","$Destination/weights","$Destination/licenses" | Out-Null
Copy-Item -LiteralPath "$ArtifactRoot/build/inference-worker.exe" -Destination "$Destination/inference-worker.exe" -Force
$binary="$ArtifactRoot/vendor/bin-cuda12"
foreach($name in @('llama.dll','ggml.dll','ggml-base.dll','ggml-cpu-x64.dll','ggml-cpu-haswell.dll','ggml-cuda.dll','libomp.dll')){
  Copy-Item -LiteralPath "$binary/$name" -Destination "$Destination/runtime/$name" -Force
}
Get-ChildItem -LiteralPath "$ArtifactRoot/vendor/cudart12" -Recurse -File -Filter '*.dll' | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination "$Destination/runtime/$($_.Name.ToLowerInvariant())" -Force}
$vs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Redist/MSVC')}} | Select-Object -First 1
$crt=Get-ChildItem -LiteralPath (Join-Path $vs.FullName 'VC/Redist/MSVC') -Recurse -Directory | Where-Object {$_.FullName -match '[\\/]x64[\\/]Microsoft.VC\d+.CRT$'} | Sort-Object FullName -Descending | Select-Object -First 1
if(!$crt){throw 'App-local x64 Microsoft CRT redistribution files are required'}
Get-ChildItem -LiteralPath $crt.FullName -File -Filter '*.dll' | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination "$Destination/runtime/$($_.Name)" -Force}
if(!$WeightsPath){$WeightsPath=Join-Path $PSScriptRoot '../../model/weights/Qwen3.5-4B-baseline-Q6_K.gguf'}
$sourceWeights=[IO.Path]::GetFullPath($WeightsPath)
$weightName='weights/'+[IO.Path]::GetFileName($sourceWeights)
if(!(Test-Path -LiteralPath "$Destination/$weightName")){New-Item -ItemType HardLink -Path "$Destination/$weightName" -Target ([IO.Path]::GetFullPath($sourceWeights)) | Out-Null}
Copy-Item -LiteralPath "$ArtifactRoot/vendor/llama.cpp-620d2225cc436ab0b001e3200e590e3b286ef9ab/LICENSE" -Destination "$Destination/licenses/llama.cpp-MIT.txt" -Force
Copy-Item -LiteralPath "$binary/LICENSE-LLVM-OpenMP" -Destination "$Destination/licenses/LLVM-OpenMP.txt" -Force
$crtLicenses=Join-Path $PSScriptRoot '../licenses/Microsoft-VC-Runtime'
New-Item -ItemType Directory -Force -Path "$Destination/licenses/Microsoft-VC-Runtime" | Out-Null
Get-ChildItem -LiteralPath $crtLicenses -File | Where-Object {$_.Name -ne 'DISTRIBUTION-RIGHTS.txt'} | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination "$Destination/licenses/Microsoft-VC-Runtime/$($_.Name)" -Force}
@'
Mini-AI worker v2 Microsoft C/C++ runtime distribution notes.
The wrapper and SentencePiece/Abseil use release static CRT linkage.
The official upstream llama.cpp DLLs also need the included release x64 CRT DLLs.
These DLLs come from the installed Visual Studio VC/Redist/MSVC/x64/Microsoft.VC*.CRT folder.
The exact runtime inventory and SHA-256 are in worker.json. No compiler, import/static library,
debug CRT, runtime source or development headers are shipped in the model package.
Primary distribution list: https://learn.microsoft.com/en-us/visualstudio/releases/2026/redistribution
The accompanying Visual Studio developer licence and C++ runtime recipient licence apply.
Redistribution remains conditional on the developer licence/eligibility and distribution requirements.
This v2 notice replaces the old static-only distribution note for this package.
'@ | Set-Content -LiteralPath "$Destination/licenses/Microsoft-VC-Runtime/DISTRIBUTION-RIGHTS.txt" -Encoding utf8
if($EnableEvidence){
  $deps=Join-Path $PSScriptRoot '../build/research-20261024/dependencies'
  Copy-Item -LiteralPath "$deps/onnxruntime-win-x64-1.31.0/lib/onnxruntime.dll" -Destination "$Destination/runtime/onnxruntime.dll" -Force
  Copy-Item -LiteralPath "$deps/onnxruntime-win-x64-1.31.0/LICENSE" -Destination "$Destination/licenses/ONNX-Runtime-LICENSE.txt" -Force
  Copy-Item -LiteralPath "$deps/onnxruntime-win-x64-1.31.0/ThirdPartyNotices.txt" -Destination "$Destination/licenses/ONNX-Runtime-ThirdPartyNotices.txt" -Force
  Copy-Item -LiteralPath "$deps/sentencepiece-0.2.2/LICENSE" -Destination "$Destination/licenses/SentencePiece-LICENSE.txt" -Force
  Copy-Item -LiteralPath "$deps/sentencepiece-0.2.2/third_party/abseil-cpp/LICENSE" -Destination "$Destination/licenses/Abseil-LICENSE.txt" -Force
}
if(!$ModelLicensePath){$ModelLicensePath=Join-Path $PSScriptRoot '../../model/licenses/Qwen3.5-4B-LICENSE.txt'}
Copy-Item -LiteralPath $ModelLicensePath -Destination (Join-Path "$Destination/licenses" ([IO.Path]::GetFileName($ModelLicensePath))) -Force
$eula="$ArtifactRoot/downloads/CUDA-12.4.1-EULA.html"
if(!(Test-Path -LiteralPath $eula)){Invoke-WebRequest -Uri 'https://docs.nvidia.com/cuda/archive/12.4.1/eula/index.html' -OutFile $eula}
Copy-Item -LiteralPath $eula -Destination "$Destination/licenses/CUDA-EULA.html" -Force
@'
CUDA 12.4 runtime redistribution documentation (unmodified NVIDIA binary DLLs).
Official release EULA: https://docs.nvidia.com/cuda/archive/12.4.1/eula/index.html
Distribution grant and conditions: sections 1.1.1 and 1.1.2.
Redistributable components: Attachment A, section 2.6, CUDA Runtime and CUDA BLAS.
The versioned Windows files correspond to cudart.dll, cublas.dll and cublasLt.dll:
cudart64_12.dll, cublas64_12.dll, cublasLt64_12.dll.
Only these runtime libraries are included; the CUDA Toolkit, compiler and driver are not bundled.
Source archives and exact DLL hashes are recorded in the build inputs / worker inventory.
Application distribution remains subject to the included NVIDIA terms.
'@ | Set-Content -LiteralPath "$Destination/licenses/CUDA-REDISTRIBUTION-NOTICE.txt" -Encoding utf8
@'
Prepared worker: Mini-AI src/model-worker (sources in the project repository).
Engine: ggml-org/llama.cpp b11669, commit 620d2225cc436ab0b001e3200e590e3b286ef9ab.
Official Windows CUDA 12.4 binary archives, unmodified DLLs; exact hashes in worker.json.
Wrapper statically links its CRT; official DLLs use the bundled Microsoft runtime.
CPU compatibility and CUDA acceleration coexist in this one package.
No network server, automatic model downloads, or installed Python/Java are required by this worker.
CUDA driver remains a system prerequisite. CUDA allocator and driver cache activity is outside the old static-worker no-write claim.
'@ | Set-Content -LiteralPath "$Destination/licenses/NOTICE.txt" -Encoding utf8
$inventory=@(Get-ChildItem -LiteralPath $Destination -Recurse -File | Where-Object {$_.Name -ne 'worker.json'} | Sort-Object FullName | ForEach-Object {
  $relative=[IO.Path]::GetRelativePath($Destination,$_.FullName).Replace('\','/');if($relative.StartsWith('runtime/')){$relative=$relative.ToLowerInvariant()}
  @{path=$relative;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$dependencies=@($inventory | Where-Object {$_.path -like 'runtime/*.dll'})
$weights=$inventory | Where-Object {$_.path -eq $weightName}
if($Backend -eq 'cpu'){$GpuBudgetBytes=0;$GpuReserveBytes=0}
$manifest=@{schemaVersion=2;protocol='enterpriseai.inference.v1';executable='inference-worker.exe';engine='llama.cpp';
revision='620d2225cc436ab0b001e3200e590e3b286ef9ab+b11669-win-cuda12.4-wrapper-v2';architecture='windows-x64';supportedIsa=@('x86-64');
weights=$weights;precision=$Precision;contextTokens=$ContextTokens;threads=$Threads;minFreeMemoryBytes=$MinFreeMemoryBytes;maxProcessMemoryBytes=$MaxProcessMemoryBytes;
backend=@{kind=$Backend;device=0;gpuMemoryBudgetBytes=$GpuBudgetBytes;reserveFreeGpuMemoryBytes=$GpuReserveBytes};dependencies=$dependencies;files=$inventory}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$Destination/worker.json" -Encoding utf8
Write-Output "Prepared v2 package: $Destination ($($dependencies.Count) DLLs, $($inventory.Count) hashed files)"
