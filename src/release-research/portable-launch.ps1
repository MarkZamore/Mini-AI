#requires -Version 5.1
param([ValidateSet('cpu','cuda')][string]$Mode = 'cpu', [switch]$NoLaunch)
$ErrorActionPreference = 'Stop'
$releasePin = '@@RELEASE_SHA256@@'
$base = [IO.Path]::GetFullPath($PSScriptRoot)
function Native-Path([string]$Path) {
    if ($Path.StartsWith('\\?\')) { return $Path }
    if ($Path.StartsWith('\\')) { return '\\?\UNC\' + $Path.Substring(2) }
    return '\\?\' + $Path
}
function Plain-Path([string]$Relative) {
    if (!$Relative -or $Relative.Contains('\') -or [IO.Path]::IsPathRooted($Relative)) { throw 'Unsafe profile path.' }
    $path = $base
    foreach ($part in $Relative.Split('/')) {
        if (!$part -or $part -in @('.','..') -or $part -match '[<>:"|?*\x00-\x1f]' -or $part -match '[ .]$') {
            throw 'Unsafe profile path.'
        }
        $path = [IO.Path]::Combine($path,$part)
        if (([IO.File]::GetAttributes((Native-Path $path)) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Linked profile path is not supported: $path"
        }
    }
    return $path
}
function Hash-File([string]$Path) {
    $input = [IO.File]::Open((Native-Path $Path),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash($input))).Replace('-','').ToLowerInvariant() }
    finally { $input.Dispose();$hash.Dispose() }
}
$operation = $null
try {
    $current = $base
    while ($current) {
        if (([IO.File]::GetAttributes((Native-Path $current)) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Linked installation directory is unsupported: $current"
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
    $lockPath = [IO.Path]::Combine($base,'portable-operation.lock')
    if ([IO.File]::Exists($lockPath) -and ([IO.File]::GetAttributes($lockPath) -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Linked operation lock is unsupported.'
    }
    $operation = [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $releasePath = Plain-Path 'portable-release.json'
    if ((Hash-File $releasePath) -ne $releasePin) { throw 'Pinned release metadata differs.' }
    $release = [IO.File]::ReadAllText($releasePath,[Text.Encoding]::UTF8) | ConvertFrom-Json
    $profiles = @($release.profiles.cpu,$release.profiles.cuda)
    foreach ($profile in $profiles) {
        $profilePath = Plain-Path $profile.path
        if ((New-Object IO.FileInfo($profilePath)).Length -ne $profile.bytes -or
            (Hash-File $profilePath) -ne $profile.sha256) { throw 'A shipped profile differs from the pinned release.' }
    }
    $selected = if ($Mode -eq 'cpu') { $release.profiles.cpu } else { $release.profiles.cuda }
    $manifestPath = Plain-Path 'model/worker.json'
    $active = Hash-File $manifestPath
    if ($active -notin @($release.profiles.cpu.sha256,$release.profiles.cuda.sha256)) {
        throw 'Current worker.json is not a shipped profile; it was preserved. Use a fresh installation.'
    }
    $appPath = Plain-Path 'Mini-AI.exe'
    if ((Hash-File $appPath) -ne $release.app_sha256) { throw 'Application EXE differs from the pinned release.' }
    $workerPath = [IO.Path]::Combine($base,'model','inference-worker.exe')
    $running = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and ($_.ExecutablePath -eq $appPath -or $_.ExecutablePath -eq $workerPath)
    })
    if ($running.Count) { throw 'Close this Mini-AI window and wait for its worker to exit before switching or launching a profile.' }
    if ($active -ne $selected.sha256) {
        $id = [Guid]::NewGuid().ToString('N')
        $temporary = [IO.Path]::Combine($base,'model',"worker.$id.profile.partial")
        $backup = [IO.Path]::Combine($base,'profiles',"previous-worker.$id.json")
        [IO.File]::Copy((Native-Path (Plain-Path $selected.path)),(Native-Path $temporary),$false)
        if ((Hash-File $temporary) -ne $selected.sha256) { throw 'Profile copy differs; current manifest was preserved.' }
        # The worker holds the manifest without FILE_SHARE_WRITE/DELETE. Windows
        # rejects replacement if a live worker acquired it after the process check.
        [IO.File]::Replace((Native-Path $temporary),(Native-Path $manifestPath),(Native-Path $backup))
        if ((Hash-File $manifestPath) -ne $selected.sha256) { throw 'Profile switch verification failed; the previous manifest remains in profiles.' }
    }
    Write-Host "Selected backend: $Mode. Full application, weights, evidence index and documents are unchanged."
    if (!$NoLaunch) { Start-Process -FilePath $appPath -WorkingDirectory $base }
    exit 0
} catch {
    Write-Host ('Launch stopped: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally { if ($null -ne $operation) { $operation.Dispose() } }
