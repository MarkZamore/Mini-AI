#requires -Version 5.1
param([Parameter(Mandatory=$true)][string]$InstallDirectory,[string]$PackageDirectory=$PSScriptRoot,
      [string]$DownloadsDirectory='', [switch]$Offline,[switch]$VerifyOnly,[switch]$NoLaunch)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$deltaPin='@@DELTA_SHA256@@';$commonPin='@@COMMON_SHA256@@'
function Bootstrap-Hash([string]$Path) {
    $current=[IO.Path]::GetFullPath($Path)
    while($current){$check=if($current.StartsWith('\\?\')){$current}elseif($current.StartsWith('\\')){'\\?\UNC\'+$current.Substring(2)}else{'\\?\'+$current};if(([IO.File]::Exists($check) -or [IO.Directory]::Exists($check)) -and ([IO.File]::GetAttributes($check) -band [IO.FileAttributes]::ReparsePoint)){throw 'Linked installer path is unsupported.'};$current=[IO.Path]::GetDirectoryName($current)}
    $native=if($Path.StartsWith('\\?\')){$Path}elseif($Path.StartsWith('\\')){'\\?\UNC\'+$Path.Substring(2)}else{'\\?\'+$Path}
    $s=[IO.File]::OpenRead($native);$h=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($h.ComputeHash($s))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose();$s.Dispose()}
}
$common=[IO.Path]::Combine($PSScriptRoot,'delta-common.ps1')
if((Bootstrap-Hash $common) -ne $commonPin){throw 'Installer helper differs from its pinned bytes.'}
. $common
Add-Type -AssemblyName System.IO.Compression
[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
$package=[IO.Path]::GetFullPath($PackageDirectory);$target=[IO.Path]::GetFullPath($InstallDirectory)
Assert-Ancestors $package;Assert-Ancestors $target
$metaPath=Safe-Path $package 'delta-release.json'
if((Hash-File $metaPath) -ne $deltaPin){throw 'Delta metadata differs from the pinned installer.'}
$meta=Read-Json $metaPath
if($meta.schema_version -ne 2 -or $meta.repository -ne 'MarkZamore/Mini-AI' -or
   $meta.configuration_id -notmatch '^[a-z0-9][a-z0-9._-]{0,95}$' -or $meta.tag -notmatch '^v[A-Za-z0-9._-]{1,95}$') {throw 'Unsupported delta identity.'}
if($meta.base.tag -ne 'v2026.10.24-local-ai' -or $meta.tag -eq $meta.base.tag){throw 'Unexpected immutable base or reused tag.'}
$baseInventoryPath=Safe-Path $package 'base-files.json';$targetInventoryPath=Safe-Path $package 'target-files.json'
if((Hash-File $baseInventoryPath) -ne $meta.base.inventory_sha256 -or (Hash-File $targetInventoryPath) -ne $meta.target.inventory_sha256){throw 'Delta inventory SHA differs.'}
$baseInventory=Read-Json $baseInventoryPath;$targetInventory=Read-Json $targetInventoryPath
if($baseInventory.configuration_id -ne $meta.base.configuration_id -or $targetInventory.configuration_id -ne $meta.configuration_id -or
   @($baseInventory.files).Count -ne $meta.base.file_count -or @($targetInventory.files).Count -ne $meta.target.file_count){throw 'Full inventory identity differs.'}
$baseRows=Row-Map $baseInventory.files $target;$targetRows=Row-Map $targetInventory.files $target
$payloadRows=Row-Map $meta.payload.files $target;$controls=Row-Map $meta.control_updates $target
$requiredControls=@('Portable-Setup.ps1','Portable-Launch.ps1','portable-release.json','portable-files.json','.mini-ai-delta-common.ps1',
                    'profiles/worker.cpu.json','profiles/worker.cuda.json','Установить-и-запустить.cmd','Запустить-на-процессоре.cmd','Запустить-на-NVIDIA.cmd')
if($controls.Count -ne $requiredControls.Count -or @($requiredControls|Where-Object {!$controls.ContainsKey($_)}).Count){throw 'Incomplete or unexpected installed control set.'}
foreach($path in $payloadRows.Keys){if(!$targetRows.ContainsKey($path) -or $path -cne $targetRows[$path].path){throw 'Unexpected payload key/case.'}}
foreach($path in $targetRows.Keys){if($path.Split('/')[0] -notin @('Mini-AI.exe','vcruntime140.dll','vcruntime140_1.dll','native','licenses','model','docs','db')){throw 'Reserved/unsupported runtime namespace.'}}
foreach($path in $baseRows.Keys){if(!$targetRows.ContainsKey($path)){throw 'This updater preserves all old runtime paths; an explicit retirement migration is required.'}}
foreach($path in $targetRows.Keys){
    $row=$targetRows[$path];$changed=!$baseRows.ContainsKey($path) -or $baseRows[$path].sha256 -ne $row.sha256 -or $baseRows[$path].bytes -ne $row.bytes
    if($baseRows.ContainsKey($path) -and $baseRows[$path].path -cne $row.path){throw 'Case-only runtime renames are not supported.'}
    if($changed -ne $payloadRows.ContainsKey($path)){throw 'Payload does not exactly cover changed runtime files.'}
    if($changed -and ($payloadRows[$path].sha256 -ne $row.sha256 -or $payloadRows[$path].bytes -ne $row.bytes)){throw 'Payload row differs from full target inventory.'}
    if($controls.ContainsKey($path)){throw 'Control/runtime path collision.'}
}
$union=@{};foreach($path in @($baseRows.Keys)+@($targetRows.Keys)+@($controls.Keys)){$union[$path]=$true}
foreach($path in $union.Keys){$parts=$path.Split('/');for($i=1;$i -lt $parts.Count;$i++){if($union.ContainsKey(($parts[0..($i-1)] -join '/'))){throw 'Cross-inventory file/directory transition.'}}}
$profileRecords=@{}
foreach($which in @('base','target')){
    foreach($modeName in @('cpu','cuda')){
        $row=$meta.$which.profiles.$modeName;$source=Safe-Path $package $row.path
        if(!(Check-File $source $row.bytes $row.sha256)){throw 'A pinned backend profile is missing.'}
        $profileRecords[$which+'-'+$modeName]=$row
    }
}
function Runtime-Check($Rows,[string]$Which,[string]$Mode) {
    foreach($path in $Rows.Keys){
        $row=if($path -eq 'model/worker.json'){$profileRecords[$Which+'-'+$Mode]}else{$Rows[$path]}
        if(!(Check-File (Safe-Path $target $path) $row.bytes $row.sha256)){throw "Required runtime file missing: $path"}
    }
}
function Control-Check {
    foreach($row in $meta.control_updates){if(!(Check-File (Safe-Path $target $row.path) $row.bytes $row.sha256)){throw 'Installed control file is missing.'}}
}
function Detect-Profile([string]$Which) {
    $path=Safe-Path $target 'model/worker.json'
    if(![IO.File]::Exists((Native-Path $path))){return ''}
    $hash=Hash-File $path;$length=Length-File $path
    foreach($kind in @('cpu','cuda')){$row=$profileRecords[$Which+'-'+$kind];if($row.sha256 -eq $hash -and $row.bytes -eq $length){return $kind}}
    return ''
}
function Target-NewRow([string]$Path,[string]$Mode) {
    if($Path -eq 'model/worker.json'){return $profileRecords['target-'+$Mode]}
    if($targetRows.ContainsKey($Path)){return $targetRows[$Path]}
    return $controls[$Path]
}
function Allowed-Old([string]$Path,$Old) {
    if($baseRows.ContainsKey($Path)){
        if($null -eq $Old){return $false}
        $allowed=if($Path -eq 'model/worker.json'){@($profileRecords['base-cpu'],$profileRecords['base-cuda'])}else{@($baseRows[$Path])}
    }else{
        if($null -eq $Old){return $true}
        $allowed=@($controls[$Path].allowed_before)+@($controls[$Path])
    }
    foreach($row in $allowed){if($row.sha256 -eq $Old.sha256 -and $row.bytes -eq $Old.bytes){return $true}}
    return $false
}
$stateRoot=Safe-Path $target ('.mini-ai-updates/'+$meta.configuration_id)
$journalPath=Safe-Path $stateRoot 'journal.json';$ownerPath=Safe-Path $stateRoot 'owner.json'
$state=$null;$operation=$null
function Action-Paths {
    $runtime=[string[]]@($payloadRows.Keys);[Array]::Sort($runtime,[StringComparer]::Ordinal)
    $other=[string[]]@($controls.Keys|Where-Object {$_ -ne 'portable-release.json'});[Array]::Sort($other,[StringComparer]::Ordinal)
    return @($runtime)+@($other)+@('portable-release.json')
}
function Validate-Journal($Value){
    if($Value.delta_sha256 -ne $deltaPin -or $Value.mode -notin @('cpu','cuda') -or $Value.phase -notin @('prepared','applying','rolling_back','rolled_back','rollback_failed','committed')){throw 'Unknown update journal; preserved.'}
    $expected=@(Action-Paths);$seen=@{}
    if(@($Value.actions).Count -ne $expected.Count){throw 'Update journal action set differs.'}
    $maxProgress=if($Value.phase -eq 'committed'){$expected.Count}else{$expected.Count-1}
    if($null -eq $Value.current_index -or [long]$Value.current_index -lt -1 -or [long]$Value.current_index -gt $maxProgress){throw 'Invalid journal progress.'}
    $index=-1
    foreach($action in $Value.actions){
        $index++
        if($action.path -cne $expected[$index] -or $seen.ContainsKey([string]$action.path) -or !(Allowed-Old $action.path $action.old)){throw 'Unsafe or unknown journal action/order; preserved.'}
        $seen[[string]$action.path]=$true;$new=Target-NewRow $action.path $Value.mode
        if($action.new.sha256 -ne $new.sha256 -or $action.new.bytes -ne $new.bytes){throw 'Journal new file differs from pinned metadata.'}
        if($action.path -eq 'model/worker.json' -and $action.old.sha256 -ne $profileRecords['base-'+$Value.mode].sha256){throw 'Journal changed the installed backend.'}
        if($null -ne $action.old -and $action.old_id -notmatch '^[a-f0-9]{8}:[a-f0-9]{16}$'){throw 'Journal lacks original file identity.'}
        if($index -le [long]$Value.current_index -and $action.new_id -notmatch '^[a-f0-9]{8}:[a-f0-9]{16}$'){throw 'Journal lacks staged file ownership.'}
    }
}
function Rollback($Value){
    $failures=New-Object 'System.Collections.Generic.List[string]'
    $Value.phase='rolling_back';try{Write-AtomicJson $journalPath $Value}catch{$failures.Add('Journal warning: '+$_.Exception.Message)}
    for($i=@($Value.actions).Count-1;$i -ge 0;$i--){
        if($i -gt [long]$Value.current_index){continue} # No intent was recorded: this path was never ours to alter.
        $action=$Value.actions[$i];$destination=Safe-Path $target $action.path
        $backup=Safe-Path $stateRoot ('backup/'+$i+'.bin');$discard=Safe-Path $stateRoot ('discarded/'+$i+'.'+[Guid]::NewGuid().ToString('N')+'.bin') $true
        try{
            if([IO.File]::Exists((Native-Path $backup))){
                if($null -eq $action.old -or !(Same-File $backup $action.old) -or (File-Identity $backup) -ne $action.old_id){throw 'Backup differs from original; preserved.'}
                if([IO.File]::Exists((Native-Path $destination))){
                    $alreadyOld=$false;try{$alreadyOld=Same-File $destination $action.old}catch{}
                    if($alreadyOld -and (File-Identity $destination) -eq $action.old_id){continue}
                    if(!(Same-File $destination $action.new) -or (File-Identity $destination) -ne $action.new_id){throw 'Current file is not our staged version; preserved.'}
                    [IO.File]::Move((Native-Path $destination),(Native-Path $discard))
                }
                [void](Safe-Path $target $action.path $true)
                [IO.File]::Move((Native-Path $backup),(Native-Path $destination))
            }elseif($null -eq $action.old){
                if([IO.File]::Exists((Native-Path $destination))){
                    if(!(Same-File $destination $action.new) -or (File-Identity $destination) -ne $action.new_id){throw 'New path is not our staged file; preserved.'}
                    [IO.File]::Move((Native-Path $destination),(Native-Path $discard))
                }
            }elseif(!(Same-File $destination $action.old) -or (File-Identity $destination) -ne $action.old_id){throw 'Original backup is missing; preserve remaining files for recovery.'}
        }catch{$failures.Add($action.path+': '+$_.Exception.Message+'; backup='+$backup+'; target='+$destination)}
    }
    $Value.phase=if($failures.Count){'rollback_failed'}else{'rolled_back'}
    try{Write-AtomicJson $journalPath $Value}catch{$failures.Add('Final journal warning: '+$_.Exception.Message)}
    if($failures.Count){throw ('Recovery needs attention; all remaining versions preserved. '+($failures -join ' | '))}
}
try{
    $lock=Safe-Path $target 'portable-operation.lock'
    if([IO.File]::Exists((Native-Path $lock)) -and (Length-File $lock) -ne 0){throw 'Unknown operation lock content; preserved.'}
    if($VerifyOnly -and ![IO.File]::Exists((Native-Path $lock))){throw 'No installed operation lock: normal setup is required before verify-only.'}
    $lockMode=if($VerifyOnly){[IO.FileMode]::Open}else{[IO.FileMode]::OpenOrCreate}
    $operation=[IO.File]::Open((Native-Path $lock),$lockMode,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    if([IO.File]::Exists((Native-Path $journalPath))){
        if(!(Check-File $ownerPath $meta.owner_bytes $meta.owner_sha256)){throw 'Update ownership marker differs.'}
        $state=Read-Json $journalPath;Validate-Journal $state
        if($state.phase -notin @('committed','rolled_back')){if($VerifyOnly){throw 'Interrupted update requires the normal updater to recover first.'};Assert-Closed $target;Rollback $state}
    }elseif([IO.Directory]::Exists((Native-Path $stateRoot))){throw 'Unknown/incomplete update state directory; preserved.'}
    $mode=Detect-Profile 'target';$alreadyTarget=$false
    if($mode){try{Runtime-Check $targetRows 'target' $mode;Control-Check;$alreadyTarget=$true}catch{if(!(Detect-Profile 'base')){throw}}}
    if($alreadyTarget){Write-Host 'The complete target installation is verified.'}
    else{
        if($VerifyOnly){throw 'This directory is not the complete target release.'}
        $mode=Detect-Profile 'base';if(!$mode){throw 'Existing worker.json is not an exact published foundation CPU/CUDA profile; preserved.'}
        Assert-Closed $target;Runtime-Check $baseRows 'base' $mode
        foreach($row in $meta.control_updates){
            $path=Safe-Path $target $row.path
            if([IO.File]::Exists((Native-Path $path))){$old=@{bytes=(Length-File $path);sha256=(Hash-File $path)};if(!(Allowed-Old $row.path $old)){throw "Unknown existing control file; preserved: $($row.path)"}}
            if(!(Check-File (Safe-Path $package $row.source) $row.bytes $row.sha256)){throw 'Packaged control file missing.'}
        }
        $payload=$meta.payload
        if($payload.name -ne 'Mini-AI.update.zip' -or $payload.url -ne ('https://github.com/MarkZamore/Mini-AI/releases/download/'+$meta.tag+'/'+$payload.name)){throw 'Unpinned delta payload URL.'}
        if(!$DownloadsDirectory){$DownloadsDirectory=[IO.Path]::Combine($package,'downloads')}
        $DownloadsDirectory=[IO.Path]::GetFullPath($DownloadsDirectory);Assert-Ancestors $DownloadsDirectory
        [void][IO.Directory]::CreateDirectory((Native-Path $DownloadsDirectory))
        $archivePath=Download-Part $payload $DownloadsDirectory
        $needed=[long]1GB;foreach($row in $meta.payload.files){$needed+=[long]$row.bytes}
        if((New-Object IO.DriveInfo((Drive-Root $target))).AvailableFreeSpace -lt $needed){throw 'Not enough free disk for verified delta staging and reserve.'}
        # Retry after successful rollback uses a NEW owned attempt directory.
        if($null -ne $state){$retained=Safe-Path $target ('.mini-ai-updates/'+$meta.configuration_id+'.retained-'+[Guid]::NewGuid().ToString('N'));[IO.Directory]::Move((Native-Path $stateRoot),(Native-Path $retained));$state=$null}
        $paths=@(Action-Paths)
        $actions=@();$indices=@{}
        for($i=0;$i -lt $paths.Count;$i++){
            $path=[string]$paths[$i];$new=Target-NewRow $path $mode;$current=Safe-Path $target $path
            $old=$null;if([IO.File]::Exists((Native-Path $current))){$old=@{bytes=(Length-File $current);sha256=(Hash-File $current)}}
            if(!(Allowed-Old $path $old)){throw 'Base changed during staging; preserved.'}
            $oldId=if($null -ne $old){File-Identity $current}else{''}
            $actions+=@{path=$path;old=$old;old_id=$oldId;new_id='';new=@{bytes=[long]$new.bytes;sha256=[string]$new.sha256}};$indices[$path]=$i
        }
        $candidateState=@{delta_sha256=$deltaPin;configuration_id=$meta.configuration_id;mode=$mode;phase='prepared';actions=$actions;current_index=-1}
        $pendingState=$stateRoot+'.preparing-'+[Guid]::NewGuid().ToString('N')
        [void][IO.Directory]::CreateDirectory((Native-Path $pendingState))
        Copy-NewVerified (Safe-Path $package 'update-owner.json') (Safe-Path $pendingState 'owner.json') $meta.owner_bytes $meta.owner_sha256
        Write-AtomicJson (Safe-Path $pendingState 'journal.json') $candidateState
        [IO.Directory]::Move((Native-Path $pendingState),(Native-Path $stateRoot));$state=$candidateState
        [void][IO.Directory]::CreateDirectory((Native-Path (Safe-Path $stateRoot 'staged')))
        [void][IO.Directory]::CreateDirectory((Native-Path (Safe-Path $stateRoot 'backup')))
        $input=[IO.File]::OpenRead((Native-Path $archivePath));$zip=New-Object IO.Compression.ZipArchive($input,[IO.Compression.ZipArchiveMode]::Read,$false)
        try{
            $seen=@{}
            foreach($entry in $zip.Entries){
                $path=[string]$entry.FullName;[void](Safe-Path $target $path)
                if(!$payloadRows.ContainsKey($path) -or $seen.ContainsKey($path) -or $path -cne $payloadRows[$path].path -or (($entry.ExternalAttributes -shr 16) -band 61440) -eq 40960){throw 'Unexpected, duplicate, unsafe or symlink ZIP entry.'}
                $row=$payloadRows[$path];if($entry.Length -ne $row.bytes){throw 'ZIP length differs.'};$seen[$path]=$true
                $staged=Safe-Path $stateRoot ('staged/'+$indices[$path]+'.bin')
                $source=$entry.Open();try{$output=[IO.File]::Open((Native-Path $staged),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);try{$source.CopyTo($output);$output.Flush($true)}finally{$output.Dispose()}}finally{$source.Dispose()}
                if(!(Check-File $staged $row.bytes $row.sha256)){throw 'Staged ZIP entry missing.'}
                if($path -eq 'model/worker.json' -and $mode -eq 'cpu'){
                    $replacement=$staged+'.cpu';$profile=$profileRecords['target-cpu'];Copy-NewVerified (Safe-Path $package $profile.path) $replacement $profile.bytes $profile.sha256
                    [IO.File]::Replace((Native-Path $replacement),(Native-Path $staged),[System.Management.Automation.Language.NullString]::Value)
                }
            }
            if($seen.Count -ne $payloadRows.Count){throw 'ZIP does not cover all changed runtime files.'}
        }finally{$zip.Dispose();$input.Dispose()}
        foreach($row in $meta.control_updates){Copy-NewVerified (Safe-Path $package $row.source) (Safe-Path $stateRoot ('staged/'+$indices[$row.path]+'.bin')) $row.bytes $row.sha256}
        for($i=0;$i -lt $actions.Count;$i++){$actions[$i].new_id=File-Identity (Safe-Path $stateRoot ('staged/'+$i+'.bin'))}
        Write-AtomicJson $journalPath $state
        Assert-Closed $target
        for($i=0;$i -lt $actions.Count;$i++){
            $action=$actions[$i];$destination=Safe-Path $target $action.path $true
            if(!(Same-File $destination $action.old) -or ($null -ne $action.old -and (File-Identity $destination) -ne $action.old_id)){throw 'A file changed before replacement; preserved.'}
            $state.phase='applying';$state.current_index=$i;Write-AtomicJson $journalPath $state
            $staged=Safe-Path $stateRoot ('staged/'+$i+'.bin')
            if($null -ne $action.old){[IO.File]::Replace((Native-Path $staged),(Native-Path $destination),(Native-Path (Safe-Path $stateRoot ('backup/'+$i+'.bin'))))}
            else{[IO.File]::Move((Native-Path $staged),(Native-Path $destination))}
            if(!(Same-File $destination $action.new)){throw 'Installed delta file verification failed.'}
        }
        Runtime-Check $targetRows 'target' $mode;Control-Check
        $state.phase='committed';$state.current_index=$actions.Count;Write-AtomicJson $journalPath $state
        Write-Host 'Update complete. All target files verified; previous owned files remain in the update backup.'
    }
    $operation.Dispose();$operation=$null
    if(!$NoLaunch -and !$VerifyOnly){
        & "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -NoProfile -ExecutionPolicy Bypass -File (Safe-Path $target 'Portable-Launch.ps1') -Mode $mode
        if($LASTEXITCODE){throw 'Installation verified, but launch failed; use the installed launch command.'}
    }
    exit 0
}catch{
    $failure=$_.Exception.Message
    if($null -ne $state -and $state.phase -notin @('committed','rolled_back')){try{Rollback $state}catch{$failure+=' | '+$_.Exception.Message}}
    Write-Host ('Update stopped: '+$failure) -ForegroundColor Red
    exit 1
}finally{if($null -ne $operation){$operation.Dispose()}}
