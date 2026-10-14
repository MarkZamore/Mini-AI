param(
    [int[]]$Masks=@(0,118,126,125,123,119,111,95,63),
    [string]$PythonExecutable=(Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'),
    [switch]$SkipBuild,
    [switch]$SkipUi
)
$ErrorActionPreference='Stop'
$source=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$build=Join-Path $source 'build'
$work=Join-Path $build 'static-native-variants'
$ids=@('assistant','database','documents','graph','inference','search','sources')
$builder=Join-Path $source 'toolchain/build_static_application.ps1'
$reportPath=Join-Path $build 'test-results/static-native-module-removal.json'
$results=[Collections.Generic.List[object]]::new()
$failure=$null
New-Item -ItemType Directory -Force -Path $work | Out-Null
function Check([bool]$Value,[string]$Message){if(!$Value){throw $Message}}
function Owned([string]$Path){
    $full=[IO.Path]::GetFullPath($Path)
    Check ($full.StartsWith($work+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) "Fixture escaped build workspace: $full"
    if(Test-Path -LiteralPath $full){Check ((Resolve-Path -LiteralPath $full).Path.StartsWith($work+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'Fixture resolves outside workspace'}
    return $full
}
function Snapshot([string]$Path){
    return (@(Get-ChildItem -LiteralPath $Path -Force -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($Path,$_.FullName)+'|'+$_.Length+'|'+(Get-FileHash -LiteralPath $_.FullName).Hash }) | Sort-Object) -join "`n"
}
function Launch([string]$Exe,[string]$Command,[int]$Expected=0){
    $text=& $PythonExecutable -B (Join-Path $PSScriptRoot 'static_native_launch.py') $Exe $Command '25' $Expected $env:SystemRoot
    Check ($LASTEXITCODE -eq 0) "Static composition launch failed: $Exe $Command"
    return ($text -join "`n" | ConvertFrom-Json)
}
function SupplyBuildInputs([string]$Copy){
    # Only build-time assets are linked; application data and fixtures are never
    # hardlinked. No command writes to any shared source archive/JAR.
    foreach($relative in @('dependencies','static-toolchain/downloads','vendor')){
        $input=Join-Path $build $relative;$target=Join-Path $Copy ('build/'+$relative)
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        foreach($file in Get-ChildItem -LiteralPath $input -File){New-Item -ItemType HardLink -Path (Join-Path $target $file.Name) -Target $file.FullName | Out-Null}
    }
    $oldJdk=Join-Path $build 'tools/graalvm-community-openjdk-25.0.2+10.1'
    $legalJdk=Join-Path $Copy 'build/tools/graalvm-community-openjdk-25.0.2+10.1'
    New-Item -ItemType Directory -Force -Path $legalJdk | Out-Null
    foreach($name in @('LICENSE.txt','LICENSE_NATIVEIMAGE.txt','THIRD_PARTY_LICENSE.txt','GRAALVM-README.md','legal')){Copy-Item -LiteralPath (Join-Path $oldJdk $name) -Destination (Join-Path $legalJdk $name) -Recurse}
}
function WriteReport {
    $report=[ordered]@{success=(!$failure -and !$SkipUi -and $results.Count -eq @($Masks|Select-Object -Unique).Count);completedAtUtc=[DateTime]::UtcNow.ToString('o');profile='windows-x64-static';results=@($results.ToArray());variants=$results.Count;error=$(if($failure){$failure.ToString()}else{$null});limitations=@('Current Windows laptop, not a clean VM.','Each composition physically removes module folders inside an isolated src/build copy and performs a clean Gradle build.','Snapshots cannot detect transient filesystem writes or registry changes; ProcMon is required separately.','Static toolchain, source archives and JAR inputs are shared read-only; original data and modules are not altered.','Long EXE paths are launched from a short valid Windows working directory.')}
    $report | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $reportPath -Encoding utf8
}
try {
    foreach($mask in @($Masks|Select-Object -Unique)){
        Check ($mask -ge 0 -and $mask -le 127) 'Invalid composition mask'
        $case=Owned (Join-Path $work ('mask-'+$mask.ToString('000')))
        $copy=Join-Path $case 'src';$image=Join-Path $case 'image'
        $selected=@(for($i=0;$i -lt $ids.Count;$i++){if($mask -band (1 -shl $i)){$ids[$i]}})
        if(!$SkipBuild){
            if(Test-Path -LiteralPath $case){Remove-Item -LiteralPath (Owned $case) -Recurse -Force}
            New-Item -ItemType Directory -Force -Path $copy,$image | Out-Null
            foreach($item in Get-ChildItem -LiteralPath $source -Force | Where-Object {$_.Name -notin @('build','.git','.gradle')}){Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $copy $item.Name) -Recurse}
            foreach($id in $ids){if($id -notin $selected){Remove-Item -LiteralPath (Owned (Join-Path $copy ('modules/'+$id))) -Recurse -Force}}
            SupplyBuildInputs $copy
            & $builder -SourceDirectory $copy -OutputDirectory $image *> (Join-Path $case 'build.log')
            Check ($LASTEXITCODE -eq 0) "Static native build failed mask $mask; inspect $case/build.log"
        }
        $exe=Join-Path $image 'EnterpriseAI.exe'
        Check (Test-Path -LiteralPath $exe -PathType Leaf) "Missing static variant EXE: $mask"
        $status=Launch $exe 'status'
        Check ((@($status.modules|Sort-Object)-join ',') -eq (@($selected|Sort-Object)-join ',')) "Module mismatch mask $mask"
        $panels=@($selected|Where-Object {$_ -in @('assistant','graph')}|Sort-Object)
        Check ((@($status.panels|Sort-Object)-join ',') -eq ($panels-join ',')) "Panel mismatch mask $mask"
        Check (!$status.historyPersistence) 'History unexpectedly persists'
        $commands=@()
        if('database' -in $selected){$commands+='database-info'}
        if('database' -in $selected -and 'documents' -in $selected){$commands+=@('catalog','document','canonical','resolve')}
        if('database' -in $selected -and 'documents' -in $selected -and 'sources' -in $selected){$commands+=@('open','open-source')}
        if('search' -in $selected){$commands+=@('search','get-fragment')}
        if('graph' -in $selected){$commands+=@('graph','neighbors')}
        if('inference' -in $selected){$commands+=@('model-status','model-generate')}
        # Module-owned diagnostic commands are part of automatic discovery too.
        Check ((@($status.commands|Sort-Object)-join ',') -eq (@($commands|Sort-Object)-join ',')) "Command mismatch mask $mask"
        $unicode=Launch $exe 'проверка-қазақ-😀' 1
        Check (!$unicode.success -and $unicode.error.code -eq 'COMMAND_ABSENT' -and $unicode.error.message.EndsWith('проверка-қазақ-😀')) 'Unicode damaged'
        $nativeInventory=Get-Content -LiteralPath (Join-Path $image 'static-library-inventory.json') -Raw|ConvertFrom-Json
        Check ((@($nativeInventory.name) -contains 'enterpriseai_sqlite') -eq ('database' -in $selected)) 'Removed SQLite static library retained'
        $transfer=Join-Path $case 'перенос қазақ ә ғ қ ң ө ұ ү һ і 😀 с пробелами'
        while((Join-Path $transfer 'EnterpriseAI.exe').Length -le 340){$transfer=Join-Path $transfer 'длинная папка құжаттар перенос приложения 😀'}
        New-Item -ItemType Directory -Force -Path $transfer | Out-Null
        Copy-Item -LiteralPath $exe -Destination (Join-Path $transfer 'EnterpriseAI.exe') -Force
        $before=Snapshot $transfer;$transferExe=Join-Path $transfer 'EnterpriseAI.exe'
        $transferred=Launch $transferExe 'status'
        Check ((@($transferred.modules|Sort-Object)-join ',') -eq (@($selected|Sort-Object)-join ',')) 'Transferred composition mismatch'
        $row=[ordered]@{mask=$mask;modules=$selected;panels=@($status.panels);commands=@($status.commands);exe=$exe;exeSha256=(Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant();exeBytes=(Get-Item -LiteralPath $exe).Length;removedFolders=$(@($ids|Where-Object {$_ -notin $selected}));staticLibraries=@($nativeInventory.name);unicodeArguments=$true;longPathLength=$transferExe.Length;uiSmoke=$false;transferredUiSmoke=$false}
        if(!$SkipUi){
            $smoke=Launch $exe 'ui-smoke';$longSmoke=Launch $transferExe 'ui-smoke'
            Check ($smoke.uiSmoke -and $longSmoke.uiSmoke) 'Static SWT UI did not complete'
            Check ([string]::IsNullOrWhiteSpace($smoke.inputNotice)) 'Short path unexpectedly restricts IME'
            Check (![string]::IsNullOrWhiteSpace($longSmoke.inputNotice)) 'Long-path IME limitation not disclosed'
            $row.uiSmoke=$true;$row.transferredUiSmoke=$true;$row['loadedModules']=@($smoke.observerLoadedModules);$row['longPathLoadedModules']=@($longSmoke.observerLoadedModules);$row['imports']=$smoke.observerPeImports
        }
        Check ((Snapshot $transfer) -ceq $before) 'Runtime altered transferred fixture'
        $row['fixtureInventoryUnchanged']=$true;$results.Add($row);WriteReport
        Write-Output "PASS static composition mask $mask; modules [$($selected -join ', ')]"
    }
} catch {$failure=$_;throw}
finally {WriteReport}
