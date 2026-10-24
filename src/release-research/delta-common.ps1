#requires -Version 5.1
# Local filesystem helpers. Import compiles a tiny inbox-.NET identity reader;
# it does not inspect or change any application/model file.
if(-not ('MiniAiDeltaIdentity' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
public static class MiniAiDeltaIdentity {
    [StructLayout(LayoutKind.Sequential)] struct Info {
        public uint Attributes,CreationLow,CreationHigh,AccessLow,AccessHigh,WriteLow,WriteHigh;
        public uint Volume,SizeHigh,SizeLow,Links,IndexHigh,IndexLow;
    }
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetFileInformationByHandle(IntPtr handle,out Info info);
    public static string Read(string path) {
        using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)) {
            Info info;
            if(!GetFileInformationByHandle(stream.SafeFileHandle.DangerousGetHandle(),out info))
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            return info.Volume.ToString("x8")+":"+info.IndexHigh.ToString("x8")+info.IndexLow.ToString("x8");
        }
    }
}
'@
}
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
    $owner=Safe-Path $Root ($Part.name+'.mini-ai.owner.json')
    if([IO.File]::Exists((Native-Path $owner))){
        $saved=Read-Json $owner
        if($saved.sha256 -ne $Part.sha256 -or $saved.bytes -ne $Part.bytes -or $saved.url -ne $Part.url){throw 'Unknown download ownership marker; preserved.'}
    }else{
        if([IO.File]::Exists((Native-Path $temporary))){throw 'Unowned partial download was preserved; use a new download directory.'}
        Write-AtomicJson $owner @{sha256=$Part.sha256;bytes=$Part.bytes;url=$Part.url}
    }
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

function Write-AtomicJson([string]$Path,$Value) {
    Assert-Ancestors $Path
    $temporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.pending'
    $bytes=(New-Object Text.UTF8Encoding($false)).GetBytes(($Value|ConvertTo-Json -Depth 16))
    $output=[IO.File]::Open((Native-Path $temporary),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$output.Write($bytes,0,$bytes.Length);$output.Flush($true)} finally {$output.Dispose()}
    # Windows PowerShell 5.1 binds ordinary $null to an empty String here.
    # NullString supplies the actual null backup name accepted by File.Replace.
    if([IO.File]::Exists((Native-Path $Path))){[IO.File]::Replace((Native-Path $temporary),(Native-Path $Path),[System.Management.Automation.Language.NullString]::Value)}
    else {[IO.File]::Move((Native-Path $temporary),(Native-Path $Path))}
}
function Copy-NewVerified([string]$Source,[string]$Target,[long]$Bytes,[string]$Hash) {
    Assert-Ancestors $Source;Assert-Ancestors $Target
    $input=[IO.File]::Open((Native-Path $Source),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        $output=[IO.File]::Open((Native-Path $Target),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try {$input.CopyTo($output);$output.Flush($true)}finally{$output.Dispose()}
    }finally{$input.Dispose()}
    [void](Check-File $Target $Bytes $Hash)
}
function Row-Map($Rows,[string]$Root) {
    $map=@{}
    foreach($row in @($Rows)) {
        [void](Safe-Path $Root $row.path)
        if($row.sha256 -notmatch '^[a-f0-9]{64}$' -or [long]$row.bytes -lt 0 -or $map.ContainsKey([string]$row.path)){throw 'Invalid/duplicate inventory entry.'}
        $map[[string]$row.path]=$row
    }
    foreach($key in @($map.Keys)) {
        $parts=$key.Split('/')
        for($i=1;$i -lt $parts.Count;$i++){if($map.ContainsKey(($parts[0..($i-1)] -join '/'))){throw 'File/directory prefix collision.'}}
    }
    return $map
}
function Same-File([string]$Path,$Row) {
    if($null -eq $Row){return ![IO.File]::Exists((Native-Path $Path))}
    return (Check-File $Path $Row.bytes $Row.sha256)
}
function Assert-Closed([string]$Root) {
    $app=Safe-Path $Root 'Mini-AI.exe';$worker=Safe-Path $Root 'model/inference-worker.exe'
    $running=@(Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -and ($_.ExecutablePath -eq $app -or $_.ExecutablePath -eq $worker)})
    if($running.Count){throw 'Close this Mini-AI window and wait for its worker to exit before updating.'}
}
function File-Identity([string]$Path){Assert-Ancestors $Path;return [MiniAiDeltaIdentity]::Read((Native-Path $Path))}
