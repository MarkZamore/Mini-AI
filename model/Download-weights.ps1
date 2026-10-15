param([string]$LocalPartsDirectory = '')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$base = $PSScriptRoot
$expectedHash = '3acaea00a98af4e8cb7195259d7dd903b0cc749dff61a8cf47274c9a91ed02f6'
$expectedBytes = [long]3464055744
$weightsDirectory = Join-Path $base 'weights'
$target = Join-Path $weightsDirectory 'Qwen3.5-4B-baseline-Q6_K.gguf'
function Hash-File([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Verify-Package {
    $manifest = Get-Content -LiteralPath (Join-Path $base 'worker.json') -Raw | ConvertFrom-Json
    if ($manifest.weights.sha256 -ne $expectedHash -or $manifest.weights.path -ne 'weights/Qwen3.5-4B-baseline-Q6_K.gguf') {
        throw 'This downloader does not match worker.json.'
    }
    foreach ($file in $manifest.files) {
        if ((Hash-File (Join-Path $base $file.path)) -ne $file.sha256) { throw "Package file differs: $($file.path)" }
    }
}
if (Test-Path -LiteralPath $target) {
    if ((Get-Item -LiteralPath $target).Length -ne $expectedBytes -or (Hash-File $target) -ne $expectedHash) {
        throw 'Existing weights differ from this package. Preserve them separately before installing this model.'
    }
    Verify-Package
    Write-Host 'Correct weights are already installed; all package hashes verified.'
    exit 0
}
$parts = @(
    @{ Name = 'model-weights.part01'; Bytes = [long]1835008000; Hash = '4acc3aebee0bc8a554541d76950ccc8bed64749ddf22b210244b08a1788b9676' },
    @{ Name = 'model-weights.part02'; Bytes = [long]1629047744; Hash = '5be094730ddc9f0a4270dcdb8010d3e1454155f88297ce52b128e30e0426a6f6' }
)
[IO.Directory]::CreateDirectory($weightsDirectory) | Out-Null
$downloadedHere = [string]::IsNullOrWhiteSpace($LocalPartsDirectory)
if ($downloadedHere) {
    $partsDirectory = Join-Path $weightsDirectory 'download-parts'
    [IO.Directory]::CreateDirectory($partsDirectory) | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} else {
    $partsDirectory = (Resolve-Path -LiteralPath $LocalPartsDirectory).Path
}
foreach ($part in $parts) {
    $partPath = Join-Path $partsDirectory $part.Name
    if (!(Test-Path -LiteralPath $partPath)) {
        if (!$downloadedHere) { throw "Missing local part: $($part.Name)" }
        $url = 'https://github.com/MarkZamore/Mini-AI/releases/download/v2026.10.15-model-interim-4b/' + $part.Name
        Write-Host "Downloading $($part.Name)..."
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $partPath
    }
    if ((Get-Item -LiteralPath $partPath).Length -ne $part.Bytes -or (Hash-File $partPath) -ne $part.Hash) {
        throw "Incomplete or incorrect part: $partPath. It was not used to install weights."
    }
}
$temporary = $target + '.assembling'
$output = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
try {
    foreach ($part in $parts) {
        $inputPart = [IO.File]::OpenRead((Join-Path $partsDirectory $part.Name))
        try { $inputPart.CopyTo($output, 1048576) } finally { $inputPart.Dispose() }
    }
} finally { $output.Dispose() }
if ((Get-Item -LiteralPath $temporary).Length -ne $expectedBytes -or (Hash-File $temporary) -ne $expectedHash) {
    throw 'Combined weights did not pass SHA-256 verification; package is not ready.'
}
[IO.File]::Move($temporary, $target)
Verify-Package
if ($downloadedHere) {
    foreach ($part in $parts) { Remove-Item -LiteralPath (Join-Path $partsDirectory $part.Name) }
}
Write-Host 'Model assembled; all manifest hashes verified. Place the entire model folder next to Mini-AI.exe.'
