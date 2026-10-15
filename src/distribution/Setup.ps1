#requires -Version 5.1
param([string]$DownloadsDirectory = '', [switch]$Offline, [switch]$NoLaunch)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$base = $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
function Native-Path([string]$Path) {
    if ($Path.StartsWith('\\?\')) { return $Path }
    if ($Path.StartsWith('\\')) { return '\\?\UNC\' + $Path.Substring(2) }
    return '\\?\' + $Path
}
function Hash-File([string]$Path) {
    $stream = [IO.File]::OpenRead((Native-Path $Path))
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
function Check-File([string]$Path, [long]$Bytes, [string]$Hash) {
    if (![IO.File]::Exists((Native-Path $Path))) { return $false }
    $stream = [IO.File]::OpenRead((Native-Path $Path))
    try { $length = $stream.Length } finally { $stream.Dispose() }
    if ($length -ne $Bytes) { throw "Existing file has a different size; preserved: $Path" }
    if ((Hash-File $Path) -ne $Hash) { throw "Existing file has a different SHA-256; preserved: $Path" }
    return $true
}
$checkedDirectories = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
[void]$checkedDirectories.Add($base)
function Safe-Target([string]$Relative) {
    $parts = $Relative.Replace('\','/').Split('/')
    if ($parts.Count -eq 0 -or @($parts | Where-Object { !$_ -or $_ -eq '.' -or $_ -eq '..' -or $_.Contains(':') }).Count) {
        throw "Unsafe package path: $Relative"
    }
    $current = $base
    for ($i=0; $i -lt $parts.Count-1; $i++) {
        $current += '\' + $parts[$i]
        if (!$checkedDirectories.Contains($current)) {
            [void][IO.Directory]::CreateDirectory((Native-Path $current))
            if (([IO.File]::GetAttributes((Native-Path $current)) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Linked installation directory is not supported: $current"
            }
            [void]$checkedDirectories.Add($current)
        }
    }
    $target = $current + '\' + $parts[-1]
    if ([IO.File]::Exists((Native-Path $target)) -and
        (([IO.File]::GetAttributes((Native-Path $target)) -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "Linked installation file is not supported: $target"
    }
    return $target
}
try {
    $runtime = Get-Content -LiteralPath (Join-Path $base 'runtime-files.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($file in $runtime.files) {
        if (!(Check-File (Safe-Target $file.path) $file.bytes $file.sha256)) { throw "Missing application file: $($file.path)" }
    }
    if (!$DownloadsDirectory) { $DownloadsDirectory = Join-Path $base 'downloads' }
    $DownloadsDirectory = [IO.Path]::GetFullPath($DownloadsDirectory)
    [void][IO.Directory]::CreateDirectory($DownloadsDirectory)
    $assets = Get-Content -LiteralPath (Join-Path $base 'download-assets.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($asset in $assets) {
        $target = Join-Path $DownloadsDirectory $asset.name
        if (Check-File $target $asset.bytes $asset.sha256) { Write-Host "Verified cache: $($asset.name)"; continue }
        if ($Offline) { throw "Missing offline download: $($asset.name)" }
        Write-Host "Downloading $($asset.name) ($([Math]::Round($asset.bytes/1GB,2)) GiB)..."
        # Bounded HTTP ranges also allow a large interrupted download to resume.
        # Never replace a differing final file or discard an existing partial file.
        $temporary = $target + '.mini-ai.partial'
        $output = [IO.File]::Open((Native-Path $temporary),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::Write)
        try {
            if ($output.Length -gt $asset.bytes) { throw "Partial download is too large; preserved: $temporary" }
            $output.Position = $output.Length
            $downloadUrl = $asset.url
            while ($output.Position -lt $asset.bytes) {
                $first = $output.Position
                $last = [Math]::Min($first + 16MB - 1, [long]$asset.bytes - 1)
                $request = [Net.HttpWebRequest]::Create($downloadUrl)
                $request.UserAgent = 'Mini-AI-setup'
                $request.Timeout = 30000
                $request.ReadWriteTimeout = 60000
                $request.AddRange([long]$first,[long]$last)
                $response = $request.GetResponse()
                try {
                    $expectedRange = "bytes $first-$last/$($asset.bytes)"
                    if ([int]$response.StatusCode -ne 206 -or $response.Headers['Content-Range'] -ne $expectedRange) {
                        throw "Server returned an unexpected download range for $($asset.name)"
                    }
                    $downloadUrl = $response.ResponseUri.AbsoluteUri
                    $input = $response.GetResponseStream()
                    try { $input.CopyTo($output,1048576) } finally { $input.Dispose() }
                    $output.Flush()
                    if ($output.Position -ne $last+1) { throw "Interrupted range; rerun to resume: $($asset.name)" }
                    if ($output.Position % 256MB -eq 0 -or $output.Position -eq $asset.bytes) {
                        Write-Host "  $([Math]::Round($output.Position/1MB)) / $([Math]::Round($asset.bytes/1MB)) MiB"
                    }
                } finally { $response.Dispose() }
            }
        } finally { $output.Dispose() }
        if (!(Check-File $temporary $asset.bytes $asset.sha256)) { throw "Download not created: $($asset.name)" }
        [IO.File]::Move($temporary, $target)
    }
    $dataManifest = Join-Path $base 'EnterpriseAI-files.json'
    if ((Hash-File $dataManifest) -ne '6e45549454e6eac449d325f516addde87d36041fa22c6c6656f65170f370f06c') { throw 'Data inventory differs from pinned release.' }
    $data = Get-Content -LiteralPath $dataManifest -Raw -Encoding UTF8 | ConvertFrom-Json
    $count = 0
    foreach ($asset in $assets | Where-Object { $_.name.EndsWith('.zip') }) {
        Write-Host "Preparing documents: $($asset.name)..."
        $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $DownloadsDirectory $asset.name))
        try {
            foreach ($file in $data.files | Where-Object { $_.asset -eq $asset.name -and ($_.path.StartsWith('docs/') -or $_.path.StartsWith('db/')) }) {
                $target = Safe-Target $file.path
                if (!(Check-File $target $file.size $file.sha256)) {
                    $entry = $archive.GetEntry($file.path)
                    if ($null -eq $entry -or $entry.Length -ne $file.size) { throw "Archive entry differs: $($file.path)" }
                    $inputStream = $entry.Open()
                    try {
                        $outputStream = [IO.File]::Open((Native-Path $target),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
                        try { $inputStream.CopyTo($outputStream,1048576) } finally { $outputStream.Dispose() }
                    } finally { $inputStream.Dispose() }
                    if (!(Check-File $target $file.size $file.sha256)) { throw "Extraction missing: $($file.path)" }
                }
                $count++
            }
        } finally { $archive.Dispose() }
    }
    $expectedCount = @($data.files | Where-Object { $_.path.StartsWith('docs/') -or $_.path.StartsWith('db/') }).Count
    if ($count -ne $expectedCount) { throw "Incomplete data: $count of $expectedCount files" }
    Write-Host "Verified all $count document/data files. Preparing model..."
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $base 'model/Download-weights.ps1') -LocalPartsDirectory $DownloadsDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Model assembly or verification failed.' }
    $modelManifest = Get-Content -LiteralPath (Join-Path $base 'model/worker.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($file in $modelManifest.files) {
        if ((Hash-File (Safe-Target ('model/' + $file.path))) -ne $file.sha256) { throw "Model file differs: $($file.path)" }
    }
    Write-Host 'READY. Documents, application and model are verified. Later, start Mini-AI.exe directly.'
    if (!$NoLaunch) { Start-Process -FilePath (Join-Path $base 'Mini-AI.exe') -WorkingDirectory $base }
    exit 0
} catch {
    Write-Host ('Preparation stopped: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Existing differing files were preserved. Use a fresh folder, or restore a complete matching download.'
    exit 1
}
