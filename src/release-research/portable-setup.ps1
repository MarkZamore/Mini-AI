#requires -Version 5.1
param(
    [string]$DownloadsDirectory = '',
    [switch]$Offline,
    [switch]$VerifyOnly,
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$releasePin = '@@RELEASE_SHA256@@'
$base = [IO.Path]::GetFullPath($PSScriptRoot)
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression

# Stream ZIP64 directly from the verified parts. No full joined archive or
# extra copy of the generator weights is ever created.
Add-Type -TypeDefinition @'
using System;
using System.IO;
public sealed class MiniAiPartStream : Stream {
    private readonly string[] paths;
    private readonly long[] starts;
    private readonly long length;
    private long position;
    private int current = -1;
    private FileStream input;
    public MiniAiPartStream(string[] files) {
        paths = files;
        starts = new long[files.Length + 1];
        for (int i=0; i<files.Length; i++)
            starts[i+1] = checked(starts[i] + new FileInfo(files[i]).Length);
        length = starts[files.Length];
    }
    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return true; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { return length; } }
    public override long Position { get { return position; } set { Seek(value, SeekOrigin.Begin); } }
    public override void Flush() {}
    public override int Read(byte[] buffer, int offset, int count) {
        if (position == length || count == 0) return 0;
        int index = Array.BinarySearch(starts, position);
        if (index < 0) index = ~index - 1;
        if (index == paths.Length) return 0;
        if (current != index) {
            if (input != null) input.Dispose();
            input = new FileStream(paths[index], FileMode.Open, FileAccess.Read, FileShare.Read);
            current = index;
        }
        input.Position = position - starts[index];
        int available = (int)Math.Min((long)count, starts[index+1] - position);
        int got = input.Read(buffer, offset, available);
        if (got == 0 && available > 0) throw new EndOfStreamException("Part changed or ended early");
        position += got;
        return got;
    }
    public override long Seek(long offset, SeekOrigin origin) {
        long target = origin == SeekOrigin.Begin ? offset :
                      origin == SeekOrigin.Current ? checked(position + offset) : checked(length + offset);
        if (target < 0 || target > length) throw new IOException("Seek outside multipart archive");
        return position = target;
    }
    protected override void Dispose(bool disposing) {
        if (disposing && input != null) input.Dispose();
        base.Dispose(disposing);
    }
    public override void SetLength(long value) { throw new NotSupportedException(); }
    public override void Write(byte[] b, int o, int c) { throw new NotSupportedException(); }
}
'@

function Native-Path([string]$Path) {
    if ($Path.StartsWith('\\?\')) { return $Path }
    if ($Path.StartsWith('\\')) { return '\\?\UNC\' + $Path.Substring(2) }
    return '\\?\' + $Path
}
function Drive-Root([string]$Path) {
    # DriveInfo accepts a conventional root, not extended syntax such as
    # \\?\C:\. Keep extended syntax for all actual filesystem operations.
    $root = [IO.Path]::GetPathRoot($Path)
    if ($root.StartsWith('\\?\UNC\',[StringComparison]::OrdinalIgnoreCase)) {
        return '\\' + $root.Substring(8)
    }
    if ($root.StartsWith('\\?\',[StringComparison]::OrdinalIgnoreCase)) {
        return $root.Substring(4)
    }
    return $root
}
function Assert-Plain([string]$Path) {
    $native = Native-Path $Path
    if ([IO.File]::Exists($native) -or [IO.Directory]::Exists($native)) {
        if (([IO.File]::GetAttributes($native) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Linked path is not supported; preserved: $Path"
        }
    }
}
function Assert-Ancestors([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        Assert-Plain $current
        $parent = [IO.Path]::GetDirectoryName($current)
        if ($parent -eq $current) { break }
        $current = $parent
    }
}
function Safe-Path([string]$Root, [string]$Relative, [bool]$CreateParents = $false) {
    if (!$Relative -or $Relative.Contains('\') -or [IO.Path]::IsPathRooted($Relative)) {
        throw "Unsafe package path: $Relative"
    }
    $parts = $Relative.Split('/')
    foreach ($part in $parts) {
        if (!$part -or $part -eq '.' -or $part -eq '..' -or $part -match '[<>:"|?*\x00-\x1f]' -or
            $part -match '[ .]$' -or $part -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?$') {
            throw "Unsafe Windows package path: $Relative"
        }
    }
    $target = $Root
    for ($i=0; $i -lt $parts.Count; $i++) {
        $target = [IO.Path]::Combine($target, $parts[$i])
        Assert-Plain $target
        if ($CreateParents -and $i -lt $parts.Count-1) {
            [void][IO.Directory]::CreateDirectory((Native-Path $target))
            Assert-Plain $target
        }
    }
    return $target
}
function Hash-File([string]$Path) {
    $stream = [IO.File]::Open((Native-Path $Path), [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
function Length-File([string]$Path) {
    $stream = [IO.File]::OpenRead((Native-Path $Path))
    try { return $stream.Length } finally { $stream.Dispose() }
}
function Check-File([string]$Path, [long]$Bytes, [string]$Hash) {
    Assert-Plain $Path
    if (![IO.File]::Exists((Native-Path $Path))) {
        if ([IO.Directory]::Exists((Native-Path $Path))) { throw "A directory occupies a file path; preserved: $Path" }
        return $false
    }
    if ((Length-File $Path) -ne $Bytes -or (Hash-File $Path) -ne $Hash) {
        throw "Existing file differs from this release; preserved: $Path"
    }
    return $true
}
function Read-Json([string]$Path) {
    return [IO.File]::ReadAllText((Native-Path $Path), [Text.Encoding]::UTF8) | ConvertFrom-Json
}
function Check-Runtime($File) {
    $path = Safe-Path $base $File.path
    if ($File.path -eq 'model/worker.json' -and [IO.File]::Exists((Native-Path $path))) {
        $actual = Hash-File $path
        foreach ($profile in @($release.profiles.cpu,$release.profiles.cuda)) {
            if ($actual -eq $profile.sha256 -and (Length-File $path) -eq $profile.bytes) { return $true }
        }
        throw 'Existing worker.json is not a shipped CPU/CUDA profile; preserved.'
    }
    return Check-File $path $File.bytes $File.sha256
}
function Write-State([string]$Path, $Value) {
    Assert-Plain $Path
    $text = $Value | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText((Native-Path $Path), $text, (New-Object Text.UTF8Encoding($false)))
}
function Download-Part($Part, [string]$Root) {
    $target = Safe-Path $Root $Part.name
    if (Check-File $target $Part.bytes $Part.sha256) { return $target }
    if ($Offline) { throw "Missing offline part: $($Part.name)" }
    $temporary = Safe-Path $Root ($Part.name + '.mini-ai.partial')
    $output = [IO.File]::Open((Native-Path $temporary), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        if ($output.Length -gt $Part.bytes) { throw "Partial download is too large; preserved: $temporary" }
        $output.Position = $output.Length
        $attempts = 0
        $buffer = New-Object byte[] 1048576
        while ($output.Position -lt $Part.bytes) {
            $first = $output.Position
            $last = [Math]::Min($first + 16MB - 1, [long]$Part.bytes - 1)
            $response = $null
            try {
                $request = [Net.HttpWebRequest]::Create([string]$Part.url)
                $request.UserAgent = 'Mini-AI-portable/1'
                $request.Timeout = 30000
                $request.ReadWriteTimeout = 60000
                $request.AddRange([long]$first,[long]$last)
                $response = $request.GetResponse()
                if ($response.ResponseUri.Scheme -ne 'https' -or [int]$response.StatusCode -ne 206 -or
                    $response.Headers['Content-Range'] -ne "bytes $first-$last/$($Part.bytes)") {
                    throw "Unexpected server range for $($Part.name)"
                }
                $input = $response.GetResponseStream()
                try {
                    [long]$remaining = $last - $first + 1
                    while ($remaining -gt 0) {
                        $got = $input.Read($buffer,0,[int][Math]::Min([long]$buffer.Length,$remaining))
                        if ($got -eq 0) { throw "Interrupted range for $($Part.name)" }
                        $output.Write($buffer,0,$got)
                        $remaining -= $got
                    }
                    if ($input.ReadByte() -ne -1) { throw "Oversized server range for $($Part.name)" }
                    $output.Flush()
                } finally { $input.Dispose() }
                $attempts = 0
                Write-Host ("  {0}: {1} / {2} MiB" -f $Part.name,[Math]::Round($output.Position/1MB),[Math]::Round($Part.bytes/1MB))
            } catch {
                $output.Flush()
                $attempts++
                if ($attempts -ge 3) { throw }
                Write-Host "Connection interrupted; resuming the saved download..."
                Start-Sleep -Seconds (2 * $attempts)
            } finally { if ($null -ne $response) { $response.Dispose() } }
        }
    } finally { $output.Dispose() }
    if (!(Check-File $temporary $Part.bytes $Part.sha256)) { throw "Download was not created: $($Part.name)" }
    [IO.File]::Move((Native-Path $temporary), (Native-Path $target))
    return $target
}

$operation = $null
try {
    Assert-Ancestors $base
    $releasePath = Safe-Path $base 'portable-release.json'
    if ((Hash-File $releasePath) -ne $releasePin) { throw 'Release metadata differs from the pinned installer.' }
    $release = Read-Json $releasePath
    if ($release.schema_version -ne 1 -or $release.repository -ne 'MarkZamore/Mini-AI' -or
        $release.tag -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,100}$') { throw 'Unsupported release metadata.' }
    $inventoryPath = Safe-Path $base 'portable-files.json'
    if ((Hash-File $inventoryPath) -ne $release.inventory_sha256) { throw 'Runtime inventory differs from the pinned release.' }
    $inventory = Read-Json $inventoryPath
    if ($inventory.schema_version -ne 1 -or $inventory.configuration_id -ne $release.configuration_id -or
        @($inventory.files).Count -ne $release.file_count) { throw 'Runtime inventory identity differs.' }
    foreach ($profile in @($release.profiles.cpu,$release.profiles.cuda)) {
        if ($profile.path -notin @('profiles/worker.cpu.json','profiles/worker.cuda.json') -or
            !(Check-File (Safe-Path $base $profile.path) $profile.bytes $profile.sha256)) {
            throw 'A shipped generation profile is missing or differs.'
        }
    }
    $lockPath = Safe-Path $base 'portable-operation.lock'
    $operation = [IO.File]::Open((Native-Path $lockPath),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $statePath = Safe-Path $base 'portable-install-state.json'
    $canResume = $false
    if ([IO.File]::Exists((Native-Path $statePath))) {
        $previous = Read-Json $statePath
        if ($previous.inventory_sha256 -ne $release.inventory_sha256) { throw 'This directory belongs to another release; use a new folder.' }
        $canResume = $true
    }
    $files = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([StringComparer]::OrdinalIgnoreCase)
    $already = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    [long]$neededRuntime = 0
    foreach ($file in $inventory.files) {
        if ($file.sha256 -notmatch '^[a-f0-9]{64}$' -or [long]$file.bytes -lt 0) { throw 'Invalid runtime inventory entry.' }
        $target = Safe-Path $base $file.path
        if ($files.ContainsKey($file.path)) { throw "Duplicate Windows path: $($file.path)" }
        $files.Add($file.path, $file)
        if (Check-Runtime $file) { [void]$already.Add($file.path) }
        else {
            $partial = Safe-Path $base ($file.path + '.mini-ai.partial')
            [long]$existing = 0
            if ([IO.File]::Exists((Native-Path $partial))) {
                if (!$canResume) { throw "An unowned partial file already exists; preserved: $partial" }
                $existing = Length-File $partial
            }
            if ($existing -gt $file.bytes) { throw "Partial extracted file is too large; preserved: $partial" }
            $neededRuntime += [long]$file.bytes - $existing
        }
    }
    if (!$files.ContainsKey('Mini-AI.exe') -or !$files.ContainsKey('model/worker.json') -or
        !$files.ContainsKey('db/index.sqlite') -or $files['Mini-AI.exe'].sha256 -ne $release.app_sha256 -or
        $files['model/worker.json'].sha256 -ne $release.model_manifest_sha256 -or
        $files['db/index.sqlite'].sha256 -ne $release.corpus_sha256) { throw 'Required runtime fingerprints differ.' }
    if ($VerifyOnly) {
        if ($already.Count -ne $files.Count) { throw "Installation is incomplete: $($already.Count) / $($files.Count)" }
        Write-Host "Verified $($files.Count) runtime files. Configuration: $($release.configuration_id)"
        exit 0
    }
    if (!$DownloadsDirectory) { $DownloadsDirectory = [IO.Path]::Combine($base,'downloads') }
    $DownloadsDirectory = [IO.Path]::GetFullPath($DownloadsDirectory)
    Assert-Ancestors $DownloadsDirectory
    [void][IO.Directory]::CreateDirectory((Native-Path $DownloadsDirectory))
    $partNames = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    [long]$neededDownloads = 0
    foreach ($part in $release.parts) {
        if ($part.name -notmatch '^Mini-AI\.payload\.part[0-9]{3,4}$' -or
            !$partNames.Add($part.name) -or $part.sha256 -notmatch '^[a-f0-9]{64}$' -or
            [long]$part.bytes -le 0 -or [long]$part.bytes -ge 2GB -or
            $part.url -cne "https://github.com/MarkZamore/Mini-AI/releases/download/$($release.tag)/$($part.name)") {
            throw 'Invalid pinned download entry.'
        }
        $target = Safe-Path $DownloadsDirectory $part.name
        if (!(Check-File $target $part.bytes $part.sha256)) {
            [long]$existing = 0
            $partial = Safe-Path $DownloadsDirectory ($part.name + '.mini-ai.partial')
            if ([IO.File]::Exists((Native-Path $partial))) {
                if (!$canResume) { throw "An unowned partial download already exists; preserved: $partial" }
                $existing = Length-File $partial
            }
            if ($existing -gt $part.bytes) { throw "Oversized partial download; preserved: $partial" }
            $neededDownloads += [long]$part.bytes - $existing
        }
    }
    if ($partNames.Count -eq 0) { throw 'Release has no payload parts.' }
    $runtimeDrive = New-Object IO.DriveInfo((Drive-Root $base))
    $downloadDrive = New-Object IO.DriveInfo((Drive-Root $DownloadsDirectory))
    if ($runtimeDrive.Name -eq $downloadDrive.Name) {
        if ($runtimeDrive.AvailableFreeSpace -lt $neededRuntime + $neededDownloads + 512MB) {
            throw "Not enough disk space. Need $([Math]::Ceiling(($neededRuntime+$neededDownloads+512MB)/1GB)) GiB free."
        }
    } elseif ($runtimeDrive.AvailableFreeSpace -lt $neededRuntime+512MB -or
              $downloadDrive.AvailableFreeSpace -lt $neededDownloads+512MB) {
        throw 'Not enough space for downloads or the installed runtime.'
    }
    Write-State $statePath @{state='preparing';inventory_sha256=$release.inventory_sha256;configuration_id=$release.configuration_id}
    $paths = New-Object 'System.Collections.Generic.List[string]'
    foreach ($part in $release.parts) { $paths.Add((Native-Path (Download-Part $part $DownloadsDirectory))) }
    $multipart = New-Object MiniAiPartStream(,$paths.ToArray())
    $archive = New-Object IO.Compression.ZipArchive($multipart,[IO.Compression.ZipArchiveMode]::Read,$false)
    try {
        if ($archive.Entries.Count -ne $files.Count) { throw 'ZIP entry count differs from the allowlist.' }
        $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $archive.Entries) {
            $relative = $entry.FullName
            $target = Safe-Path $base $relative $true
            if (!$seen.Add($relative) -or !$files.ContainsKey($relative)) { throw "Unexpected archive entry: $relative" }
            $file = $files[$relative]
            if ($entry.Length -ne $file.bytes) { throw "ZIP entry size differs: $relative" }
            if ($already.Contains($relative)) { continue }
            $temporary = Safe-Path $base ($relative + '.mini-ai.partial') $true
            $input = $entry.Open()
            try {
                $output = [IO.File]::Open((Native-Path $temporary),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::Write,[IO.FileShare]::None)
                try {
                    [long]$skip = $output.Length
                    if ($skip -gt $entry.Length) { throw "Oversized partial extracted file; preserved: $temporary" }
                    $buffer = New-Object byte[] 1048576
                    while ($skip -gt 0) {
                        $got = $input.Read($buffer,0,[int][Math]::Min([long]$buffer.Length,$skip))
                        if ($got -eq 0) { throw "Interrupted archive prefix: $relative" }
                        $skip -= $got
                    }
                    $output.Position = $output.Length
                    $input.CopyTo($output,1048576)
                    $output.Flush()
                } finally { $output.Dispose() }
            } finally { $input.Dispose() }
            if (!(Check-File $temporary $file.bytes $file.sha256)) { throw "Extraction incomplete: $relative" }
            [IO.File]::Move((Native-Path $temporary),(Native-Path $target))
            [void]$already.Add($relative)
            if ($already.Count % 100 -eq 0) { Write-Host "Prepared $($already.Count) / $($files.Count) files..." }
        }
        if ($seen.Count -ne $files.Count -or $already.Count -ne $files.Count) { throw 'Incomplete archive extraction.' }
    } finally { $archive.Dispose(); $multipart.Dispose() }
    Write-State $statePath @{state='ready';inventory_sha256=$release.inventory_sha256;configuration_id=$release.configuration_id;files=$files.Count}
    Write-Host "READY. Verified all $($files.Count) runtime files."
    $operation.Dispose(); $operation = $null
    $nativePowerShell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $launchArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',[IO.Path]::Combine($base,'Portable-Launch.ps1'),'-Mode','cpu')
    if ($NoLaunch) { $launchArgs += '-NoLaunch' }
    & $nativePowerShell @launchArgs
    if ($LASTEXITCODE -ne 0) { throw 'CPU profile preparation or launch failed; installed files were preserved.' }
    exit 0
} catch {
    Write-Host ('Preparation stopped: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Existing files were preserved. Rerun to resume an interrupted download or extraction.'
    Write-Host 'If an existing complete file differs, use a fresh folder and a matching release.'
    exit 1
} finally { if ($null -ne $operation) { $operation.Dispose() } }
