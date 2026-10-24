#requires -Version 5.1
param([string]$DownloadsDirectory='', [switch]$Offline,[switch]$VerifyOnly,[switch]$NoLaunch)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$pin='@@RELEASE_SHA256@@';$commonPin='@@COMMON_SHA256@@';$base=[IO.Path]::GetFullPath($PSScriptRoot)
function Verify-Native([string]$Path){if($Path.StartsWith('\\?\')){return $Path};if($Path.StartsWith('\\')){return '\\?\UNC\'+$Path.Substring(2)};return '\\?\'+$Path}
function Verify-Hash([string]$Path){
    $current=[IO.Path]::GetFullPath($Path)
    while($current){$native=Verify-Native $current;if(([IO.File]::Exists($native) -or [IO.Directory]::Exists($native)) -and ([IO.File]::GetAttributes($native) -band [IO.FileAttributes]::ReparsePoint)){throw 'Linked installed verification path is unsupported.'};$current=[IO.Path]::GetDirectoryName($current)}
    $s=[IO.File]::OpenRead((Verify-Native $Path));$h=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($h.ComputeHash($s))).Replace('-','').ToLowerInvariant()}finally{$s.Dispose();$h.Dispose()}
}
$common=[IO.Path]::Combine($base,'.mini-ai-delta-common.ps1')
if((Verify-Hash $common) -ne $commonPin){throw 'Installed verification helper differs.'}
. $common
$operation=$null
try {
    Assert-Ancestors $base
    $releasePath=Safe-Path $base 'portable-release.json'
    if((Hash-File $releasePath) -ne $pin){throw 'Installed release metadata differs.'}
    $release=Read-Json $releasePath;$inventoryPath=Safe-Path $base 'portable-files.json'
    if((Hash-File $inventoryPath) -ne $release.inventory_sha256){throw 'Installed full inventory differs.'}
    $inventory=Read-Json $inventoryPath;$rows=Row-Map $inventory.files $base
    if($inventory.configuration_id -ne $release.configuration_id -or $rows.Count -ne $release.file_count){throw 'Installed inventory identity differs.'}
    $lock=Safe-Path $base 'portable-operation.lock'
    if(![IO.File]::Exists((Native-Path $lock)) -or (Length-File $lock) -ne 0){throw 'Known installation operation lock is missing or differs.'}
    $operation=[IO.File]::Open((Native-Path $lock),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $active=Hash-File (Safe-Path $base 'model/worker.json');$mode=''
    foreach($kind in @('cpu','cuda')){
        $profile=$release.profiles.$kind
        if(!(Check-File (Safe-Path $base $profile.path) $profile.bytes $profile.sha256)){throw 'Installed backend profile missing.'}
        if($active -eq $profile.sha256){$mode=$kind}
    }
    if(!$mode){throw 'Active worker.json is not one of the two known profiles; preserved.'}
    foreach($path in $rows.Keys){$row=if($path -eq 'model/worker.json'){$release.profiles.$mode}else{$rows[$path]};if(!(Check-File (Safe-Path $base $path) $row.bytes $row.sha256)){throw "Required file missing: $path"}}
    Write-Host 'Все файлы установленной версии проверены.'
    $operation.Dispose();$operation=$null
    if(!$VerifyOnly -and !$NoLaunch){& "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -NoProfile -ExecutionPolicy Bypass -File (Safe-Path $base 'Portable-Launch.ps1') -Mode $mode;if($LASTEXITCODE){throw 'Проверка завершена, запуск остановлен.'}}
    exit 0
}catch{Write-Host ('Проверка остановлена: '+$_.Exception.Message) -ForegroundColor Red;exit 1}
finally{if($null -ne $operation){$operation.Dispose()}}
