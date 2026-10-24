#requires -Version 5.1
param(
    [string]$InstallDirectory = '',
    [string]$DownloadsDirectory = '',
    [switch]$Offline,
    [switch]$VerifyOnly,
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$deltaPin = '@@DELTA_SHA256@@'
$commonPin = '@@COMMON_SHA256@@'
$applyPin = '@@APPLY_SHA256@@'
$packageRoot = [IO.Path]::GetFullPath($PSScriptRoot)

# These three helpers intentionally precede the common script: no unverified
# code is dot-sourced merely to obtain its own integrity check.
function Bootstrap-NativePath([string]$Path) {
    if ($Path.StartsWith('\\?\')) { return $Path }
    if ($Path.StartsWith('\\')) { return '\\?\UNC\' + $Path.Substring(2) }
    return '\\?\' + $Path
}
function Bootstrap-PlainAncestors([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        $native = Bootstrap-NativePath $current
        if ([IO.File]::Exists($native) -or [IO.Directory]::Exists($native)) {
            if (([IO.File]::GetAttributes($native) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Linked bootstrap path is unsupported; preserved: $current"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ($parent -eq $current) { break }
        $current = $parent
    }
}
function Bootstrap-Hash([string]$Path) {
    Bootstrap-PlainAncestors $Path
    $input = [IO.File]::Open((Bootstrap-NativePath $Path),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($input))).Replace('-','').ToLowerInvariant() }
    finally { $input.Dispose(); $algorithm.Dispose() }
}

function Get-FoundationZip($Setup, [string]$CacheRoot) {
    # Prefer the tiny original ZIP shipped inside the new bootstrap. Neither
    # installed model bytes nor a second full runtime are copied here.
    $embedded = Safe-Path $packageRoot 'foundation-setup.zip'
    if ([IO.File]::Exists((Native-Path $embedded))) {
        if (!(Check-File $embedded ([long]$Setup.bytes) ([string]$Setup.sha256))) { throw 'The embedded foundation setup is missing.' }
        return $embedded
    }
    Assert-Ancestors $CacheRoot
    $cached = Safe-Path $CacheRoot 'foundation-setup.zip'
    if ([IO.File]::Exists((Native-Path $cached))) {
        if (!(Check-File $cached ([long]$Setup.bytes) ([string]$Setup.sha256))) { throw 'The cached foundation setup is missing.' }
        return $cached
    }
    if ($Offline) { throw 'The pinned foundation-setup.zip is required for an offline clean installation.' }
    [void][IO.Directory]::CreateDirectory((Native-Path $CacheRoot))
    Assert-Ancestors $CacheRoot
    $temporary = Safe-Path $CacheRoot ('foundation-setup.' + [Guid]::NewGuid().ToString('N') + '.partial')
    $response = $null
    $output = $null
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $request = [Net.HttpWebRequest]::Create([string]$Setup.url)
        $request.UserAgent = 'Mini-AI-delta-bootstrap/2'
        $request.Timeout = 30000
        $request.ReadWriteTimeout = 60000
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200 -or $response.ResponseUri.Scheme -ne 'https') {
            throw 'Unexpected response for the pinned foundation setup.'
        }
        if ($response.ContentLength -ge 0 -and $response.ContentLength -ne [long]$Setup.bytes) {
            throw 'The foundation setup download length differs.'
        }
        $input = $response.GetResponseStream()
        try {
            $output = [IO.File]::Open((Native-Path $temporary),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            $buffer = New-Object byte[] 65536
            [long]$remaining = [long]$Setup.bytes
            while ($remaining -gt 0) {
                $got = $input.Read($buffer,0,[int][Math]::Min([long]$buffer.Length,$remaining))
                if ($got -eq 0) { throw 'The foundation setup download was interrupted.' }
                $output.Write($buffer,0,$got)
                $remaining -= $got
            }
            if ($input.ReadByte() -ne -1) { throw 'The foundation setup download is oversized.' }
            $output.Flush($true)
        } finally {
            if ($null -ne $output) { $output.Dispose(); $output = $null }
            $input.Dispose()
        }
        if (!(Check-File $temporary ([long]$Setup.bytes) ([string]$Setup.sha256))) { throw 'The foundation setup download is incomplete.' }
        [IO.File]::Move((Native-Path $temporary),(Native-Path $cached))
        return $cached
    } finally {
        if ($null -ne $output) { $output.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
        # An interrupted temporary is preserved. It is never trusted or reused
        # as a completed download by a later operation.
    }
}

function Copy-FoundationBootstrap([string]$ZipPath, [string]$Target, $Setup) {
    Add-Type -AssemblyName System.IO.Compression
    Assert-Ancestors $ZipPath
    $zipInput = [IO.File]::Open((Native-Path $ZipPath),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    $archive = $null
    try {
        # Keep the verified input open without write/delete sharing throughout
        # archive inspection; do not extract a different file after hashing.
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try { $actualHash = ([BitConverter]::ToString($algorithm.ComputeHash($zipInput))).Replace('-','').ToLowerInvariant() }
        finally { $algorithm.Dispose() }
        if ($zipInput.Length -ne [long]$Setup.bytes -or $actualHash -ne [string]$Setup.sha256) {
            throw 'The pinned foundation setup changed before extraction.'
        }
        $zipInput.Position = 0
        $archive = New-Object IO.Compression.ZipArchive($zipInput,[IO.Compression.ZipArchiveMode]::Read,$true)
        if ($archive.Entries.Count -eq 0 -or $archive.Entries.Count -gt 128) { throw 'Unexpected foundation bootstrap entry count.' }
        $entries = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([StringComparer]::OrdinalIgnoreCase)
        [long]$expanded = 0
        foreach ($entry in $archive.Entries) {
            if (!$entry.FullName.StartsWith('Mini-AI/',[StringComparison]::Ordinal)) {
                throw 'A foundation bootstrap entry is outside Mini-AI/.'
            }
            $relative = $entry.FullName.Substring(8)
            # The pinned original setup contains files only. Empty names,
            # explicit directory entries and all unsafe Windows paths fail.
            $destination = Safe-Path $Target $relative
            if ($entries.ContainsKey($relative) -or $entry.Length -lt 0 -or $entry.Length -gt 16MB) {
                throw 'A foundation bootstrap entry is duplicated or oversized.'
            }
            foreach ($seen in $entries.Keys) {
                if ($relative.StartsWith($seen + '/', [StringComparison]::OrdinalIgnoreCase) -or
                    $seen.StartsWith($relative + '/', [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'A foundation bootstrap file/directory prefix collides.'
                }
            }
            $expanded += $entry.Length
            if ($expanded -gt 64MB) { throw 'The foundation bootstrap expands beyond its small-file budget.' }
            $input = $entry.Open()
            $memory = New-Object IO.MemoryStream
            try { $input.CopyTo($memory,65536); $bytes = $memory.ToArray() }
            finally { $input.Dispose(); $memory.Dispose() }
            if ($bytes.LongLength -ne $entry.Length) { throw 'A foundation bootstrap entry is incomplete.' }
            $algorithm = [Security.Cryptography.SHA256]::Create()
            try { $hash = ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-','').ToLowerInvariant() }
            finally { $algorithm.Dispose() }
            # Check every existing destination before adding any bootstrap file.
            # Unknown user files never become overwrite permission.
            $exists = Check-File $destination $entry.Length $hash
            $entries.Add($relative,@{path=$relative;bytes=$bytes;sha256=$hash;exists=$exists})
        }
        foreach ($required in @('Portable-Setup.ps1','Portable-Launch.ps1','portable-release.json','portable-files.json',
                                'profiles/worker.cpu.json','profiles/worker.cuda.json')) {
            if (!$entries.ContainsKey($required)) { throw "The foundation bootstrap omits $required" }
        }
        Assert-Ancestors $Target
        [void][IO.Directory]::CreateDirectory((Native-Path $Target))
        foreach ($item in $entries.Values) {
            if ($item.exists) { continue }
            $destination = Safe-Path $Target $item.path $true
            $temporary = Safe-Path $Target ($item.path + '.' + [Guid]::NewGuid().ToString('N') + '.foundation.partial') $true
            $output = [IO.File]::Open((Native-Path $temporary),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            try { $output.Write($item.bytes,0,$item.bytes.Length); $output.Flush($true) }
            finally { $output.Dispose() }
            if (!(Check-File $temporary $item.bytes.LongLength $item.sha256)) { throw 'A bootstrap copy differs from its verified ZIP.' }
            # Move is non-overwriting. A concurrent/unknown destination is
            # preserved; a later run can reuse only complete exact copies.
            [IO.File]::Move((Native-Path $temporary),(Native-Path $destination))
        }
    } finally {
        if ($null -ne $archive) { $archive.Dispose() }
        $zipInput.Dispose()
    }
}

try {
    if ($deltaPin -notmatch '^[a-f0-9]{64}$' -or $commonPin -notmatch '^[a-f0-9]{64}$' -or
        $applyPin -notmatch '^[a-f0-9]{64}$') { throw 'The bootstrap was not pinned by the release packager.' }
    Bootstrap-PlainAncestors $packageRoot
    $commonPath = [IO.Path]::Combine($packageRoot,'Delta-Common.ps1')
    if ((Bootstrap-Hash $commonPath) -ne $commonPin) { throw 'The pinned common installer code differs.' }
    . $commonPath
    $metadataPath = Safe-Path $packageRoot 'delta-release.json'
    if ((Hash-File $metadataPath) -ne $deltaPin) { throw 'The pinned delta release metadata differs.' }
    $delta = Read-Json $metadataPath
    if ($delta.schema_version -ne 2 -or $delta.repository -ne 'MarkZamore/Mini-AI' -or
        $delta.tag -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,100}$' -or
        $delta.tag -eq 'v2026.10.24-local-ai' -or
        $delta.configuration_id -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,150}$' -or
        $delta.target.app_sha256 -notmatch '^[a-f0-9]{64}$' -or
        $delta.base.inventory_sha256 -notmatch '^[a-f0-9]{64}$') { throw 'Unsupported delta release identity.' }
    $setup = $delta.base.setup
    if ($setup.path -cne 'foundation-setup.zip' -or $setup.sha256 -notmatch '^[a-f0-9]{64}$' -or
        [long]$setup.bytes -le 0 -or [long]$setup.bytes -gt 8MB -or
        $setup.url -cne 'https://github.com/MarkZamore/Mini-AI/releases/download/v2026.10.24-local-ai/Mini-AI-setup.zip') {
        throw 'The foundation bootstrap is not the pinned published setup.'
    }
    $applyPath = Safe-Path $packageRoot 'Delta-Apply.ps1'
    if ((Hash-File $applyPath) -ne $applyPin) { throw 'The pinned delta transaction engine differs.' }
    if (!$InstallDirectory) {
        $beside = Safe-Path $packageRoot 'Mini-AI.exe'
        $InstallDirectory = if ([IO.File]::Exists((Native-Path $beside))) { $packageRoot } else { [IO.Path]::Combine($packageRoot,'App') }
    }
    $InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory)
    Assert-Ancestors $InstallDirectory
    if (!$DownloadsDirectory) { $DownloadsDirectory = [IO.Path]::Combine($packageRoot,'downloads') }
    $DownloadsDirectory = [IO.Path]::GetFullPath($DownloadsDirectory)
    Assert-Ancestors $DownloadsDirectory
    $nativePowerShell = [IO.Path]::Combine($env:SystemRoot,'System32','WindowsPowerShell','v1.0','powershell.exe')

    if (!$VerifyOnly) {
        $appPath = Safe-Path $InstallDirectory 'Mini-AI.exe'
        $updatesPath = Safe-Path $InstallDirectory '.mini-ai-updates'
        if ([IO.File]::Exists((Native-Path $updatesPath))) { throw 'A file occupies the update journal directory; preserved.' }
        $interruptedDelta = [IO.Directory]::Exists((Native-Path $updatesPath))
        $bootstrap = ![IO.File]::Exists((Native-Path $appPath)) -and !$interruptedDelta
        if (!$interruptedDelta) {
            $statePath = Safe-Path $InstallDirectory 'portable-install-state.json'
            if ([IO.File]::Exists((Native-Path $statePath))) {
                $state = Read-Json $statePath
                if ($state.inventory_sha256 -ne $delta.base.inventory_sha256 -or
                    $state.state -notin @('preparing','ready') -or
                    ($delta.base.configuration_id -and $state.configuration_id -ne $delta.base.configuration_id)) {
                    # A ready target installation is owned by the transaction
                    # engine, which recognises its exact target state itself.
                    if ($state.inventory_sha256 -ne $delta.target.inventory_sha256 -or $state.state -ne 'ready') {
                        throw 'An unknown foundation installation state was preserved.'
                    }
                } elseif ($state.state -eq 'preparing') {
                    # The old installer can write EXE before the rest of its
                    # runtime. Resume that exact pinned incomplete foundation.
                    $bootstrap = $true
                }
            }
        }
        if ($bootstrap) {
            $zip = Get-FoundationZip $setup $DownloadsDirectory
            Copy-FoundationBootstrap $zip $InstallDirectory $setup
            $oldSetupPath = Safe-Path $InstallDirectory 'Portable-Setup.ps1'
            $baseArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$oldSetupPath,'-NoLaunch',
                          '-DownloadsDirectory',$DownloadsDirectory)
            if ($Offline) { $baseArgs += '-Offline' }
            & $nativePowerShell @baseArgs
            if ($LASTEXITCODE -ne 0) { throw 'Foundation preparation stopped; its verified files and resume state were preserved.' }
        }
    }
    # Recheck engine immediately before execution. It owns the target operation
    # lock, recovery, exact target verification, backend selection and launch.
    if ((Hash-File $applyPath) -ne $applyPin) { throw 'The pinned delta engine changed before execution.' }
    $applyArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$applyPath,
                   '-InstallDirectory',$InstallDirectory,'-PackageDirectory',$packageRoot,
                   '-DownloadsDirectory',$DownloadsDirectory)
    if ($Offline) { $applyArgs += '-Offline' }
    if ($VerifyOnly) { $applyArgs += '-VerifyOnly' }
    if ($NoLaunch) { $applyArgs += '-NoLaunch' }
    & $nativePowerShell @applyArgs
    if ($LASTEXITCODE -ne 0) { throw 'The delta update did not complete; its transaction engine preserved recovery information.' }
    exit 0
} catch {
    Write-Host ('Preparation stopped: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Existing files were preserved. Rerun the same pinned installer to resume a recognised interrupted operation.'
    exit 1
}
