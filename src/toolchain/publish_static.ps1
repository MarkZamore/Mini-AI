param([string]$AcceptanceReport='')
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent $PSScriptRoot
$projectRoot=Split-Path -Parent $sourceRoot
$buildRoot=Join-Path $sourceRoot 'build'
$image=Join-Path $buildRoot 'static-image/EnterpriseAI.exe'
if(!$AcceptanceReport){$AcceptanceReport=Join-Path $buildRoot 'test-results/static-release-acceptance.json'}
if(!(Test-Path -LiteralPath $AcceptanceReport)){throw 'Final static release acceptance report is required before publication.'}
$acceptance=Get-Content -LiteralPath $AcceptanceReport -Raw | ConvertFrom-Json
$sha=(Get-FileHash -LiteralPath $image -Algorithm SHA256).Hash.ToLowerInvariant()
if(!$acceptance.completed -or $acceptance.exeSha256 -ne $sha){throw 'Acceptance does not certify this exact EXE.'}
$buildManifest=Get-Content -LiteralPath (Join-Path $buildRoot 'static-image/build-manifest.json') -Raw | ConvertFrom-Json
if($buildManifest.executableSha256 -ne $sha){throw 'Build manifest belongs to another EXE.'}
$currentSourceFiles=@(Get-ChildItem -LiteralPath $sourceRoot -File)+@(Get-ChildItem -LiteralPath $sourceRoot -Directory | Where-Object {$_.Name -notin @('build','.git','.gradle')} | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -File -Recurse})
$currentSourceFiles=@($currentSourceFiles | Where-Object {$_.FullName -notmatch '[\\/]__pycache__[\\/]'})
if($currentSourceFiles.Count -ne @($buildManifest.sources).Count){throw 'Source file set changed after build.'}
foreach($source in $buildManifest.sources){
    $path=[IO.Path]::GetFullPath((Join-Path $sourceRoot $source.path))
    if(!$path.StartsWith($sourceRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe source snapshot path.'}
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $source.sha256){throw "Source changed after build: $($source.path)"}
}
if((Get-FileHash -LiteralPath (Join-Path $buildRoot 'libs/EnterpriseAI-1.0.0.jar') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $buildManifest.applicationJarSha256){throw 'Application JAR changed after build.'}
foreach($archive in @($buildManifest.toolchainArchives)+@($buildManifest.applicationArchives)){
    $archivePath=$archive.path
    if(!$archivePath){
        if($archive.name -in @('jvm.lib','libchelper.lib')){
            $archivePath=Join-Path $buildRoot ('static-toolchain/graalvm-static/lib/svm/clibraries/windows-amd64/'+$archive.name)
        }elseif($archive.name -in @('java.lib','net.lib','nio.lib','zip.lib','extnet.lib','sunmscapi.lib')){
            $archivePath=Join-Path $buildRoot ('static-toolchain/graalvm-static/lib/static/windows-amd64/'+$archive.name)
        }else{throw 'Unknown toolchain archive in build manifest.'}
    }
    $archivePath=[IO.Path]::GetFullPath($archivePath)
    if(!$archivePath.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Runtime archive is outside the isolated build.'}
    if((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $archive.sha256){throw 'Runtime archive changed after build.'}
}
foreach($condition in @('imports','relocation','search','workers','graph','modules','procmon','licenses','data')){
    if($acceptance.conditions.$condition -ne $true){throw "Missing acceptance: $condition"}
}
foreach($evidence in $acceptance.evidence){
    $path=[IO.Path]::GetFullPath((Join-Path $sourceRoot $evidence.path))
    if(!$path.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must be under src/build.'}
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evidence.sha256){throw 'Acceptance evidence changed.'}
}
$destination=Join-Path $projectRoot 'EnterpriseAI.exe'
if(@(Get-CimInstance Win32_Process -Filter "Name='EnterpriseAI.exe'" | Where-Object {$_.ExecutablePath -eq $destination}).Count){throw 'Close the published EnterpriseAI before replacing it.'}
$ledger=Join-Path $projectRoot 'licenses/application-files.json'
$owned=@()
if(Test-Path -LiteralPath $ledger){$owned=@(Get-Content -LiteralPath $ledger -Raw | ConvertFrom-Json)}
if('licenses\application-files.json' -notin $owned -and (Test-Path -LiteralPath $ledger)){$owned+='licenses\application-files.json'}
if('EnterpriseAI.exe' -notin $owned){$owned+='EnterpriseAI.exe'}
foreach($relative in $owned){
    if([IO.Path]::IsPathRooted($relative) -or $relative.Contains(':') -or @($relative -split '[\\/]' | Where-Object {$_ -in @('.','..')}).Count){throw 'Unsafe old application inventory.'}
    if($relative -notin @('EnterpriseAI.exe','vcruntime140.dll','vcruntime140_1.dll') -and $relative -notmatch '^(native|licenses|app|runtime)[\\/]'){throw 'Inventory contains data or unknown application files.'}
    $path=[IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
    if(!$path.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Inventory target escaped the project.'}
    $ancestor=Split-Path -Parent $path
    while($ancestor -ne $projectRoot){
        if((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Owned application directory is a link; refusing deletion.'}
        $ancestor=Split-Path -Parent $ancestor
    }
}
$recovery=Join-Path $buildRoot ('publication/recovery-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $recovery | Out-Null
foreach($relative in $owned){
    $path=Join-Path $projectRoot $relative
    if(Test-Path -LiteralPath $path -PathType Leaf){
        $backup=Join-Path $recovery $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $backup) | Out-Null
        Copy-Item -LiteralPath $path -Destination $backup
    }
}
$next=Join-Path $recovery 'next-static.exe'
Copy-Item -LiteralPath $image -Destination $next
Move-Item -LiteralPath $next -Destination $destination -Force
foreach($relative in $owned){
    if($relative -eq 'EnterpriseAI.exe'){continue}
    $path=Join-Path $projectRoot $relative
    if(Test-Path -LiteralPath $path -PathType Leaf){Remove-Item -LiteralPath $path -Force}
}
foreach($folder in @('native','licenses','app','runtime')){
    $path=[IO.Path]::GetFullPath((Join-Path $projectRoot $folder))
    if(!(Test-Path -LiteralPath $path -PathType Container)){continue}
    if(!$path.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe owned directory.'}
    $directories=@(Get-ChildItem -LiteralPath $path -Directory -Recurse -Force | Sort-Object { $_.FullName.Length } -Descending)+@(Get-Item -LiteralPath $path)
    foreach($directory in $directories){
        if($directory.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing to remove a linked directory.'}
        if(@(Get-ChildItem -LiteralPath $directory.FullName -Force).Count -eq 0){Remove-Item -LiteralPath $directory.FullName -Force}
    }
}
if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha){throw 'Published image hash mismatch.'}
$record=@{completed=$true;exeSha256=$sha;files=@('EnterpriseAI.exe');recovery=$recovery;acceptance=$AcceptanceReport;dataDirectoriesTouched=$false}
$record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $buildRoot 'test-results/static-publication.json') -Encoding UTF8
Write-Output 'Published the exact accepted EXE. Recovery files remain under src/build/publication.'
