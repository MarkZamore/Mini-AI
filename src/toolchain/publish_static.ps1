param([string]$AcceptanceReport='',[string]$ImageDirectory='')
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent $PSScriptRoot
$projectRoot=Split-Path -Parent $sourceRoot
$buildRoot=Join-Path $sourceRoot 'build'
$executableName='Mini-AI.exe'
$legacyExecutableNames=@('EnterpriseAI.exe','миниИИ.exe','miniAI.exe')
if($ImageDirectory -and ![IO.Path]::IsPathRooted($ImageDirectory)){throw 'ImageDirectory must be an absolute build-artifact path.'}
$imageDirectory=if($ImageDirectory){[IO.Path]::GetFullPath($ImageDirectory)}else{Join-Path $buildRoot 'static-image'}
if(!$imageDirectory.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'ImageDirectory must be inside src/build.'}
if(!(Test-Path -LiteralPath $imageDirectory -PathType Container)){throw 'Compiled image directory is missing.'}
$imageAncestor=$imageDirectory
while($imageAncestor -ne $buildRoot){
    if((Get-Item -LiteralPath $imageAncestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Compiled image directory is a link; refusing publication.'}
    $imageAncestor=Split-Path -Parent $imageAncestor
}
$image=Join-Path $imageDirectory $executableName
if(!$AcceptanceReport){$AcceptanceReport=Join-Path $buildRoot 'test-results/static-release-acceptance.json'}
if(!(Test-Path -LiteralPath $AcceptanceReport)){throw 'Final static release acceptance report is required before publication.'}
$acceptance=Get-Content -LiteralPath $AcceptanceReport -Raw | ConvertFrom-Json
$sha=(Get-FileHash -LiteralPath $image -Algorithm SHA256).Hash.ToLowerInvariant()
if(!$acceptance.completed -or $acceptance.exeSha256 -ne $sha){throw 'Acceptance does not certify this exact EXE.'}
$buildManifest=Get-Content -LiteralPath (Join-Path $imageDirectory 'build-manifest.json') -Raw | ConvertFrom-Json
if($buildManifest.executableSha256 -ne $sha){throw 'Build manifest belongs to another EXE.'}
if($buildManifest.executableName -ne $executableName){throw 'Build manifest has another application basename.'}
if($buildManifest.applicationIcon.sourcePath -ne 'core/shell/resources/enterpriseai/ui/application.ico' -or $buildManifest.applicationIcon.resourceGroup -ne 101 -or $buildManifest.applicationIcon.sourceSha256 -ne (Get-FileHash -LiteralPath (Join-Path $sourceRoot $buildManifest.applicationIcon.sourcePath) -Algorithm SHA256).Hash.ToLowerInvariant() -or $buildManifest.applicationIcon.sha256 -ne (Get-FileHash -LiteralPath (Join-Path $buildRoot 'static-toolchain/application-native/swt-icons.res') -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Application icon resources changed after build.'}
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
foreach($condition in @('imports','relocation','search','workers','graph','licenses','data')){
    if($acceptance.conditions.$condition -ne $true){throw "Missing acceptance: $condition"}
}
foreach($evidence in $acceptance.evidence){
    $path=[IO.Path]::GetFullPath((Join-Path $sourceRoot $evidence.path))
    if(!$path.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must be under src/build.'}
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evidence.sha256){throw 'Acceptance evidence changed.'}
}
$destination=Join-Path $projectRoot $executableName
$publishedPaths=@(@($executableName)+@($legacyExecutableNames) | ForEach-Object {Join-Path $projectRoot $_})
if(@(Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -in $publishedPaths}).Count){throw 'Close the published application before replacing it.'}
$ledger=Join-Path $projectRoot 'licenses/application-files.json'
$owned=@()
if(Test-Path -LiteralPath $ledger){$owned=@(Get-Content -LiteralPath $ledger -Raw | ConvertFrom-Json)}
if('licenses\application-files.json' -notin $owned -and (Test-Path -LiteralPath $ledger)){$owned+='licenses\application-files.json'}
# The previous accepted publication establishes ownership of the old basename.
# A renamed/unknown executable must never be deleted or overwritten on its name alone.
$previousPublicationPath=Join-Path $buildRoot 'test-results/static-publication.json'
$previousPublication=$null
$previousExecutableHashes=@{}
if(Test-Path -LiteralPath $previousPublicationPath){$previousPublication=Get-Content -LiteralPath $previousPublicationPath -Raw | ConvertFrom-Json}
foreach($name in (@($legacyExecutableNames)+@($executableName))){
    $path=Join-Path $projectRoot $name
    if(!(Test-Path -LiteralPath $path)){continue}
    if(!(Test-Path -LiteralPath $path -PathType Leaf) -or ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Published application path is not an ordinary file.'}
    if(!$previousPublication -or !$previousPublication.completed -or $name -notin @($previousPublication.files) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $previousPublication.exeSha256){throw 'Existing application EXE is not the exact previous accepted publication; refusing replacement or removal.'}
    $previousExecutableHashes[$name]=$previousPublication.exeSha256
    if($name -notin $owned){$owned+=$name}
}
$owned=@($owned | Sort-Object -Unique)
foreach($relative in $owned){
    if([IO.Path]::IsPathRooted($relative) -or $relative.Contains(':') -or @($relative -split '[\\/]' | Where-Object {$_ -in @('.','..')}).Count){throw 'Unsafe old application inventory.'}
    if($relative -notin (@($legacyExecutableNames)+@($executableName,'vcruntime140.dll','vcruntime140_1.dll')) -and $relative -notmatch '^(native|licenses|app|runtime)[\\/]'){throw 'Inventory contains data or unknown application files.'}
    $path=[IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
    if(!$path.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Inventory target escaped the project.'}
    if((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Owned application file is a link; refusing replacement or deletion.'}
    $ancestor=Split-Path -Parent $path
    while($ancestor -ne $projectRoot){
        if((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Owned application directory is a link; refusing deletion.'}
        $ancestor=Split-Path -Parent $ancestor
    }
}
$recovery=Join-Path $buildRoot ('publication/recovery-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $recovery | Out-Null
if($previousPublication){Copy-Item -LiteralPath $previousPublicationPath -Destination (Join-Path $recovery 'previous-publication.json')}
foreach($relative in $owned){
    $path=Join-Path $projectRoot $relative
    if(Test-Path -LiteralPath $path -PathType Leaf){
        $backup=Join-Path $recovery $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $backup) | Out-Null
        Copy-Item -LiteralPath $path -Destination $backup
        if($previousExecutableHashes.ContainsKey($relative) -and (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash.ToLowerInvariant() -ne $previousExecutableHashes[$relative]){throw 'Previous EXE changed during recovery backup; publication stopped.'}
    }
}
$next=Join-Path $recovery 'next-static.exe'
Copy-Item -LiteralPath $image -Destination $next
if((Get-FileHash -LiteralPath $next -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha){throw 'Candidate changed during publication copy.'}
foreach($name in $previousExecutableHashes.Keys){
    if((Get-FileHash -LiteralPath (Join-Path $projectRoot $name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $previousExecutableHashes[$name]){throw 'Previous EXE changed after backup; publication stopped.'}
}
Move-Item -LiteralPath $next -Destination $destination -Force
if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha){throw 'Published image hash mismatch; previous files remain in recovery.'}
$removedFiles=@()
foreach($relative in $owned){
    if($relative -eq $executableName){continue}
    $path=Join-Path $projectRoot $relative
    if(Test-Path -LiteralPath $path -PathType Leaf){
        if($previousExecutableHashes.ContainsKey($relative) -and (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $previousExecutableHashes[$relative]){throw 'Previous EXE changed before removal; recovery is retained.'}
        Remove-Item -LiteralPath $path -Force;$removedFiles+=$relative
    }
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
$record=@{completed=$true;exeSha256=$sha;files=@($executableName);removedFiles=$removedFiles;recovery=$recovery;acceptance=$AcceptanceReport;dataDirectoriesTouched=$false}
$record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $buildRoot 'test-results/static-publication.json') -Encoding UTF8
Write-Output 'Published the exact accepted EXE. Recovery files remain under src/build/publication.'
