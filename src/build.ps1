param(
    [ValidateSet('build','clean','test','native-libraries','native-image','static-image','stage-native','app-image','publish')][string]$Action='build',
    [string]$JdkHome='',
    [ValidateSet('native','static','jvm')][string]$Profile='static',
    [string]$PythonExecutable=$env:ENTERPRISE_TEST_PYTHON
)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$sourceRoot=$PSScriptRoot
$projectRoot=Split-Path -Parent $sourceRoot
$buildRoot=Join-Path $sourceRoot 'build'
$applicationName='миниИИ'
$executableName=$applicationName+'.exe'
Set-Location -LiteralPath $sourceRoot
New-Item -ItemType Directory -Force -Path $buildRoot,(Join-Path $buildRoot 'dependencies'),(Join-Path $buildRoot 'tools'),(Join-Path $buildRoot 'tmp') | Out-Null
$env:TEMP=Join-Path $buildRoot 'tmp'
$env:TMP=Join-Path $buildRoot 'tmp'
$env:ENTERPRISE_BUILD_POWERSHELL=(Get-Process -Id $PID).Path
function CheckExit([string]$Operation){if($LASTEXITCODE -ne 0){throw "$Operation failed with exit code $LASTEXITCODE"}}
function Download([string]$Url,[string]$Path,[string]$Hash='') {
    if(!(Test-Path -LiteralPath $Path)){Invoke-WebRequest $Url -OutFile $Path}
    if($Hash -and (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Hash.ToLowerInvariant()){throw "Checksum mismatch: $Path"}
}
function FindJdk {
    if($JdkHome){return (Resolve-Path -LiteralPath $JdkHome).Path}
    $bundled=Join-Path $buildRoot 'tools/graalvm-community-openjdk-25.0.2+10.1'
    if(Test-Path -LiteralPath (Join-Path $bundled 'bin/javac.exe')){return $bundled}
    if($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))){return $env:JAVA_HOME}
    throw 'Provide -JdkHome with Java 25 or place portable GraalVM CE 25.0.2 in src/build/tools.'
}
$javaRoot=FindJdk
$env:JAVA_HOME=$javaRoot
function PrepareSwt {
    $base='https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.swt.win32.win32.x86_64/3.135.0/'
    $original=Join-Path $buildRoot 'dependencies/swt-3.135.0.jar'
    Download ($base+'org.eclipse.swt.win32.win32.x86_64-3.135.0.jar') $original '625165af55eb5f553b28a74455038bb01343e4acf00d0857ff78c31d0b9c75be'
    $patched=Join-Path $buildRoot 'dependencies/swt-3.135.0-patched.jar'
    $patchHash=(Get-FileHash -LiteralPath 'patches/org/eclipse/swt/internal/Library.java' -Algorithm SHA256).Hash
    $marker=Join-Path $buildRoot 'dependencies/swt-patch.sha256'
    if((Test-Path -LiteralPath $patched) -and (Test-Path -LiteralPath $marker) -and (Get-Content -LiteralPath $marker -Raw).Trim() -eq $patchHash){return}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $patchClasses=Join-Path $buildRoot 'swt-patch-classes'; New-Item -ItemType Directory -Force -Path $patchClasses | Out-Null
    & (Join-Path $javaRoot 'bin/javac.exe') -encoding UTF-8 -classpath $original -d $patchClasses 'patches/org/eclipse/swt/internal/Library.java'; CheckExit 'SWT loader patch'
    $next=Join-Path $buildRoot 'dependencies/swt-3.135.0-patched.next.jar'
    Copy-Item -LiteralPath $original -Destination $next -Force
    $jar=[IO.Compression.ZipFile]::Open($next,[IO.Compression.ZipArchiveMode]::Update)
    try {
        @($jar.Entries) | Where-Object { $_.FullName -like '*.dll' -or $_.FullName -eq 'org/eclipse/swt/internal/Library.class' -or $_.FullName -match '^META-INF/.+\.(SF|RSA|DSA)$' } | ForEach-Object { $_.Delete() }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($jar,(Join-Path $patchClasses 'org/eclipse/swt/internal/Library.class'),'org/eclipse/swt/internal/Library.class') | Out-Null
    } finally { $jar.Dispose() }
    Move-Item -LiteralPath $next -Destination $patched -Force
    Set-Content -LiteralPath $marker -Value $patchHash -Encoding ASCII
}
function CompilerArguments {
    $vs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Tools/MSVC')} } | Select-Object -First 1
    if(!$vs){throw 'MSVC with Windows SDK is required only for building the application.'}
    $msvc=Get-ChildItem (Join-Path $vs.FullName 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
    $kits='C:/Program Files (x86)/Windows Kits/10'
    $sdk=Get-ChildItem (Join-Path $kits 'Include') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'um/windows.h')} | Sort-Object Name -Descending | Select-Object -First 1
    if(!$sdk){throw 'Windows SDK required for build.'}
    $env:PATH=(Join-Path $msvc.FullName 'bin/Hostx64/x64')+';'+$env:PATH
    $env:INCLUDE=@((Join-Path $msvc.FullName 'include'),(Join-Path $sdk.FullName 'ucrt'),(Join-Path $sdk.FullName 'um'),(Join-Path $sdk.FullName 'shared')) -join ';'
    $env:LIB=@((Join-Path $msvc.FullName 'lib/x64'),(Join-Path $kits "Lib/$($sdk.Name)/ucrt/x64"),(Join-Path $kits "Lib/$($sdk.Name)/um/x64")) -join ';'
    return @('/nologo','/LD','/O2','/MT',('/I'+(Join-Path $javaRoot 'include')),('/I'+(Join-Path $javaRoot 'include/win32')))
}
function BuildNative {
    $native=Join-Path $buildRoot 'native';New-Item -ItemType Directory -Force -Path $native | Out-Null
    CopySwtNatives $native
    $argsBase=CompilerArguments
    & cl.exe @argsBase 'core/platform-windows/native/platform.c' ("/Fo$native/") /link ("/OUT:$native/enterpriseai-platform.dll") ("/IMPLIB:$native/enterpriseai-platform.lib") shell32.lib ole32.lib imm32.lib;CheckExit 'Windows platform DLL'
    if(Test-Path -LiteralPath 'modules/database/module.json'){
        $vendor=Join-Path $buildRoot 'vendor';New-Item -ItemType Directory -Force -Path $vendor | Out-Null
        $zip=Join-Path $vendor 'sqlite3530100.zip'
        Download 'https://www.sqlite.org/2026/sqlite-amalgamation-3530100.zip' $zip '36ad6e7f38540a3b21a2ac36340833f0a9e426bc1c752751c3ba669466827eae'
        $amalgamation=Join-Path $vendor 'sqlite-amalgamation-3530100'
        if(!(Test-Path (Join-Path $amalgamation 'sqlite3.c'))){Expand-Archive -LiteralPath $zip -DestinationPath $vendor}
        & cl.exe @argsBase /DSQLITE_ENABLE_FTS5 /DSQLITE_TEMP_STORE=3 /DSQLITE_OMIT_LOAD_EXTENSION /DSQLITE_THREADSAFE=1 /DSQLITE_OS_WIN=1 ("/I$amalgamation") 'modules/database/native/sqlite_jni.c' (Join-Path $amalgamation 'sqlite3.c') ("/Fo$native/") /link ("/OUT:$native/enterpriseai-sqlite.dll") ("/IMPLIB:$native/enterpriseai-sqlite.lib");CheckExit 'Read-only SQLite DLL'
    }
}
function CopySwtNatives([string]$Destination) {
    $swtNativeArchive=Join-Path $buildRoot 'dependencies/swt-3.135.0.jar'
    Download 'https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.swt.win32.win32.x86_64/3.135.0/org.eclipse.swt.win32.win32.x86_64-3.135.0.jar' $swtNativeArchive '625165af55eb5f553b28a74455038bb01343e4acf00d0857ff78c31d0b9c75be'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($swtNativeArchive)
    try {foreach($entry in $archive.Entries){if($entry.FullName -match '^swt-(win32|gdip-win32|osversion-win32)-4974r10\.dll$'){[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $Destination $entry.Name),$true)}}}finally{$archive.Dispose()}
}
function ResetStage {
    $staging=Join-Path $buildRoot 'staging/EnterpriseAI'
    if(Test-Path -LiteralPath $staging){$resolved=(Resolve-Path -LiteralPath $staging).Path;if(!$resolved.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe staging path'};Remove-Item -LiteralPath $resolved -Recurse -Force}
    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    return $staging
}
function StageNative([string]$Stage) {
    # The Native Image Windows runtime imports MSVC even though our JNI DLLs use /MT.
    # App-local copies beside the EXE satisfy the OS loader before Java code can run.
    $redistributionVs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Redist/MSVC')}} | Select-Object -First 1
    if(!$redistributionVs){throw 'Licensed Visual Studio MSVC redistributable directory is required for portable packaging'}
    $redistributionVersion=Get-ChildItem (Join-Path $redistributionVs.FullName 'VC/Redist/MSVC') -Directory | Where-Object {$_.Name -match '^\d+\.'} | Sort-Object {[version]$_.Name} -Descending | Select-Object -First 1
    $redistributionFolder=Join-Path $redistributionVersion.FullName 'x64/Microsoft.VC145.CRT'
    foreach($runtimeName in @('vcruntime140.dll','vcruntime140_1.dll')){Copy-Item -LiteralPath (Join-Path $redistributionFolder $runtimeName) -Destination (Join-Path $Stage $runtimeName)}
    $destination=Join-Path $Stage 'native';New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -LiteralPath (Join-Path $buildRoot 'native/enterpriseai-platform.dll') -Destination $destination
    if(Test-Path -LiteralPath 'modules/database/module.json'){Copy-Item -LiteralPath (Join-Path $buildRoot 'native/enterpriseai-sqlite.dll') -Destination $destination}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jar=[IO.Compression.ZipFile]::OpenRead((Join-Path $buildRoot 'dependencies/swt-3.135.0.jar'))
    try {foreach($entry in $jar.Entries){if($entry.FullName -match '^swt-(win32|gdip-win32|osversion-win32)-4974r10\.dll$'){[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $destination $entry.Name),$true)}}}finally{$jar.Dispose()}
    $licenses=Join-Path $Stage 'licenses';New-Item -ItemType Directory -Force -Path $licenses | Out-Null
    $runtimeLicenses=Join-Path $licenses 'Microsoft-VC-Runtime';New-Item -ItemType Directory -Force -Path $runtimeLicenses | Out-Null
    $runtimeLicense=Join-Path $buildRoot 'dependencies/Microsoft-V14-Runtime-License-2026.docx'
    Download 'https://visualstudio.microsoft.com/wp-content/uploads/2025/10/Visual-C-V14-License-Redistributable_and_Runtime_ENU.docx' $runtimeLicense '08651651a7602fc7c0e2763de0fde1ff9f868df2780597cd1775ee9d6441c783'
    Copy-Item -LiteralPath $runtimeLicense -Destination $runtimeLicenses
    $runtimeArchive=[IO.Compression.ZipFile]::OpenRead($runtimeLicense)
    try {$runtimeReader=[IO.StreamReader]::new($runtimeArchive.GetEntry('word/document.xml').Open());try{[xml]$runtimeXml=$runtimeReader.ReadToEnd()}finally{$runtimeReader.Dispose()}}finally{$runtimeArchive.Dispose()}
    $runtimeNamespaces=[Xml.XmlNamespaceManager]::new($runtimeXml.NameTable);$runtimeNamespaces.AddNamespace('w','http://schemas.openxmlformats.org/wordprocessingml/2006/main')
    $runtimeXml.SelectNodes('//w:p',$runtimeNamespaces)|ForEach-Object {($_.SelectNodes('.//w:t',$runtimeNamespaces)|ForEach-Object {$_.InnerText})-join ''}|Set-Content -LiteralPath (Join-Path $runtimeLicenses 'LICENSE.txt') -Encoding UTF8
    $runtimeNotice=@('Microsoft Visual C++ V14 Runtime, x64, unmodified app-local files.',('Source: licensed Visual Studio 2026 VC/Redist/MSVC/'+$redistributionVersion.Name+'/x64/Microsoft.VC145.CRT'),'Redistribution list: https://learn.microsoft.com/en-us/visualstudio/releases/2026/redistribution','App-local deployment: https://learn.microsoft.com/en-us/cpp/windows/determining-which-dlls-to-redistribute?view=msvc-170','UCRT API-set DLLs are Windows 10/11 OS components; no installer or runtime setup is executed.')
    foreach($runtimeName in @('vcruntime140.dll','vcruntime140_1.dll')){$runtimeFile=Get-Item -LiteralPath (Join-Path $Stage $runtimeName);$runtimeNotice+=($runtimeName+' version '+$runtimeFile.VersionInfo.FileVersion+' SHA256 '+(Get-FileHash -LiteralPath $runtimeFile.FullName -Algorithm SHA256).Hash)}
    $runtimeNotice|Set-Content -LiteralPath (Join-Path $runtimeLicenses 'NOTICE.txt') -Encoding UTF8
    Copy-Item -LiteralPath 'patches/org/eclipse/swt/internal/Library.java' -Destination (Join-Path $licenses 'SWT-Library-modified.java')
    Copy-Item -LiteralPath 'build/generated/module-inventory.json' -Destination (Join-Path $licenses 'module-inventory.json')
    if(Test-Path -LiteralPath 'licenses'){Get-ChildItem -LiteralPath 'licenses' | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $licenses -Recurse}}
    $graalLicense=Join-Path $licenses 'GraalVM-CE';New-Item -ItemType Directory -Force -Path $graalLicense | Out-Null
    foreach($name in @('LICENSE.txt','LICENSE_NATIVEIMAGE.txt','THIRD_PARTY_LICENSE.txt','GRAALVM-README.md')){if(Test-Path -LiteralPath (Join-Path $javaRoot $name)){Copy-Item -LiteralPath (Join-Path $javaRoot $name) -Destination $graalLicense}}
    Copy-Item -LiteralPath (Join-Path $javaRoot 'legal') -Destination $graalLicense -Recurse
    Copy-Item -LiteralPath 'build-logic/WindowsLibraryLoaderSubstitution.java' -Destination $graalLicense
    $swtLicense=Join-Path $licenses 'SWT';New-Item -ItemType Directory -Force -Path $swtLicense | Out-Null
    $swtSources=Join-Path $buildRoot 'dependencies/swt-3.135.0-sources.jar'
    Download 'https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.swt.win32.win32.x86_64/3.135.0/org.eclipse.swt.win32.win32.x86_64-3.135.0-sources.jar' $swtSources
    Copy-Item -LiteralPath $swtSources -Destination (Join-Path $swtLicense 'upstream-sources.jar')
    $epl=Join-Path $buildRoot 'dependencies/EPL-2.0.txt';Download 'https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt' $epl
    Copy-Item -LiteralPath $epl -Destination $swtLicense
    $jar=[IO.Compression.ZipFile]::OpenRead((Join-Path $buildRoot 'dependencies/swt-3.135.0.jar'))
    try{foreach($entry in $jar.Entries){if(!$entry.FullName.EndsWith('/') -and ($entry.FullName -eq 'about.html' -or $entry.FullName.StartsWith('about_files/'))){$target=Join-Path $swtLicense $entry.FullName;New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target)|Out-Null;[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)}}}finally{$jar.Dispose()}
    foreach($module in Get-ChildItem -LiteralPath 'modules' -Directory){if(Test-Path -LiteralPath (Join-Path $module.FullName 'module.json')){if(Test-Path -LiteralPath (Join-Path $module.FullName 'licenses')){Copy-Item -LiteralPath (Join-Path $module.FullName 'licenses') -Destination (Join-Path $licenses $module.Name) -Recurse}}}
}
function BuildNativeImage {
    if(!(Test-Path -LiteralPath (Join-Path $javaRoot 'bin/native-image.cmd'))){throw 'Use portable GraalVM CE 25.0.2 for native-image.'}
    CompilerArguments | Out-Null
    $image=Join-Path $buildRoot 'native-image'
    if(Test-Path -LiteralPath $image){$resolvedImage=(Resolve-Path -LiteralPath $image).Path;if(!$resolvedImage.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe image path'};Remove-Item -LiteralPath $resolvedImage -Recurse -Force}
    New-Item -ItemType Directory -Force -Path $image | Out-Null
    $classpath=@((Join-Path $buildRoot 'libs/EnterpriseAI-1.0.0.jar'))+(Get-ChildItem (Join-Path $buildRoot 'runtime-libs') -Filter '*.jar').FullName
    $cp=$classpath -join ';'
    $buildClasses=Join-Path $buildRoot 'build-logic-classes'
    if(Test-Path -LiteralPath $buildClasses){$resolvedClasses=(Resolve-Path -LiteralPath $buildClasses).Path;if(!$resolvedClasses.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe build helper path'};Remove-Item -LiteralPath $resolvedClasses -Recurse -Force}
    New-Item -ItemType Directory -Force -Path $buildClasses | Out-Null
    $svm=Join-Path $javaRoot 'lib/svm/builder/svm.jar'
    & (Join-Path $javaRoot 'bin/javac.exe') -classpath ($cp+';'+$svm) -d $buildClasses 'build-logic/NativeMetadata.java' 'build-logic/NoFileLogFeature.java' 'build-logic/NoFileLogHandler.java' 'build-logic/WindowsLibraryLoaderSubstitution.java';CheckExit 'Native Image build helpers'
    $metadata=Join-Path $buildRoot 'native-metadata'
    & (Join-Path $javaRoot 'bin/java.exe') -classpath ($buildClasses+';'+$cp) NativeMetadata (Join-Path $buildRoot 'dependencies/swt-3.135.0-patched.jar') $metadata;CheckExit 'SWT JNI metadata'
    $arguments=@('--no-fallback','--enable-native-access=ALL-UNNAMED','-O2','-march=x86-64','-R:MaxHeapSize=1073741824','-H:+UnlockExperimentalVMOptions','--features=ai.enterprise.build.NoFileLogFeature','--initialize-at-build-time=ai.enterprise.build.NoFileLogHandler','--initialize-at-run-time=org.eclipse.swt,ai.enterprise.platform.WindowsPlatform',('--class-path='+$buildClasses+';'+$cp),('-H:Path='+$image),'-H:Name=EnterpriseAI',('-H:JNIConfigurationFiles='+(Join-Path $metadata 'jni-config.json')),('-H:ReflectionConfigurationFiles='+(Join-Path $metadata 'reflect-config.json')),'-H:IncludeResources=enterpriseai/(legal|ui)/.*','-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS','-H:NativeLinkerOption=/ENTRY:mainCRTStartup','-J-Xmx8g','-J-XX:-UsePerfData','ai.enterprise.bootstrap.Main')
    $arguments=@('-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.windows.headers=ALL-UNNAMED')+$arguments
    & (Join-Path $javaRoot 'bin/native-image.cmd') @arguments;CheckExit 'Native Image'
    StageNativeImage
}
function StageNativeImage {
    $image=Join-Path $buildRoot 'native-image'
    if(!(Test-Path -LiteralPath (Join-Path $image 'EnterpriseAI.exe'))){throw 'No compiled native executable to stage'}
    $stage=ResetStage;Copy-Item -LiteralPath (Join-Path $image 'EnterpriseAI.exe') -Destination (Join-Path $stage $executableName);StageNative $stage
    Set-Content -LiteralPath (Join-Path $stage 'licenses/distribution-profile.txt') -Value 'native-image Java25 / GraalVM CE25.0.2; runtime write qualification requires acceptance tests.' -Encoding UTF8
}
function BuildAppImage {
    $input=Join-Path $buildRoot 'app-image-input';New-Item -ItemType Directory -Force -Path $input | Out-Null
    Get-ChildItem -LiteralPath $input -File | Remove-Item -Force
    Copy-Item -LiteralPath (Join-Path $buildRoot 'libs/EnterpriseAI-1.0.0.jar') -Destination $input
    Copy-Item -Path (Join-Path $buildRoot 'runtime-libs/*.jar') -Destination $input
    $stage=ResetStage;$parent=Split-Path -Parent $stage;Remove-Item -LiteralPath $stage -Force
    $generatedStage=[IO.Path]::GetFullPath((Join-Path $parent $applicationName))
    if(Test-Path -LiteralPath $generatedStage){
        if(!$generatedStage.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or ((Get-Item -LiteralPath $generatedStage -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Unsafe generated app-image path'}
        Remove-Item -LiteralPath $generatedStage -Recurse -Force
    }
    $options=@('--type','app-image','--name',$applicationName,'--dest',$parent,'--input',$input,'--main-jar','EnterpriseAI-1.0.0.jar','--main-class','ai.enterprise.bootstrap.Main','--app-version','1.0.0','--vendor',$applicationName,'--icon',(Join-Path $sourceRoot 'core/shell/resources/enterpriseai/ui/application.ico'),'--java-options','-Denterprise.root=$BINDIR','--java-options','-Xmx1g','--java-options','-Xlog:disable','--java-options','-XX:-UsePerfData','--java-options','-XX:+DisableAttachMechanism','--java-options','-XX:-CreateCoredumpOnCrash','--java-options','-XX:-HeapDumpOnOutOfMemoryError','--java-options','-XX:ErrorFile=NUL','--java-options','--enable-native-access=ALL-UNNAMED')
    & (Join-Path $javaRoot 'bin/jpackage.exe') @options;CheckExit 'Portable app-image'
    Move-Item -LiteralPath $generatedStage -Destination $stage
    StageNative $stage
    Set-Content -LiteralPath (Join-Path $stage 'licenses/distribution-profile.txt') -Value 'jpackage app-image; bundled Java runtime. Runtime write qualification requires acceptance tests.' -Encoding UTF8
}
function Publish {
    $stage=Join-Path $buildRoot 'staging/EnterpriseAI'
    if(!(Test-Path -LiteralPath (Join-Path $stage $executableName))){throw 'Build a complete staging image first.'}
    $files=@(Get-ChildItem -LiteralPath $stage -File -Recurse | ForEach-Object { $_.FullName.Substring($stage.Length+1) })
    $previous=Join-Path $projectRoot 'licenses/application-files.json'
    if(Test-Path -LiteralPath $previous){
        foreach($relative in (Get-Content -LiteralPath $previous -Raw | ConvertFrom-Json)){
            if($relative -in $files -or $relative -eq 'licenses\application-files.json'){continue}
            if($relative -notin @($executableName,'EnterpriseAI.exe','vcruntime140.dll','vcruntime140_1.dll') -and $relative -notmatch '^(native|licenses|app|runtime)[\\/]'){throw 'Invalid application inventory path'}
            $target=[IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
            if(!$target.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe application inventory target'}
            if(Test-Path -LiteralPath $target){if(!(Test-Path -LiteralPath $target -PathType Leaf)){throw 'Expected owned application file'};Remove-Item -LiteralPath $target -Force}
        }
    }
    # Publish application files only. Never copy, remove or replace docs/db/model.
    foreach($entry in Get-ChildItem -LiteralPath $stage){if($entry.Name -in @('docs','db','model')){throw 'Data directory unexpectedly present in application staging'};Copy-Item -LiteralPath $entry.FullName -Destination $projectRoot -Recurse -Force}
    @($files)+@('licenses\application-files.json') | ConvertTo-Json | Set-Content -LiteralPath $previous -Encoding UTF8
}
if($Action -eq 'native-libraries'){BuildNative;exit}
if($Action -eq 'static-image'){& (Join-Path $sourceRoot 'toolchain/build_static_application.ps1');CheckExit 'Static EXE build';exit}
if($Action -eq 'native-image'){BuildNativeImage;exit}
if($Action -eq 'stage-native'){StageNativeImage;exit}
if($Action -eq 'app-image'){BuildAppImage;exit}
if($Action -eq 'publish'){
    if($Profile -eq 'static'){& (Join-Path $sourceRoot 'toolchain/publish_static.ps1');exit}
    Publish;exit
}
PrepareSwt
$gradle=Join-Path $buildRoot 'tools/gradle-9.8.1/bin/gradle.bat'
if(!(Test-Path -LiteralPath $gradle)){Download 'https://services.gradle.org/distributions/gradle-9.8.1-bin.zip' (Join-Path $buildRoot 'tools/gradle-9.8.1-bin.zip') 'dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f';Expand-Archive -LiteralPath (Join-Path $buildRoot 'tools/gradle-9.8.1-bin.zip') -DestinationPath (Join-Path $buildRoot 'tools') -Force}
$tasks=if($Action -eq 'clean'){@('clean')}elseif($Action -eq 'test'){@('testClasses','copyRuntime','nativeLibraries','moduleArtifacts','coreArtifact')}elseif($Profile -eq 'jvm'){@('appImage','moduleArtifacts','coreArtifact')}elseif($Profile -eq 'static'){@('staticImage','moduleArtifacts','coreArtifact')}else{@('nativeImage','moduleArtifacts','coreArtifact')}
& $gradle --no-daemon --project-cache-dir (Join-Path $buildRoot 'gradle-project-cache') --gradle-user-home (Join-Path $buildRoot 'gradle-home') @tasks;CheckExit 'Gradle build'
if($Action -eq 'test'){& (Join-Path $sourceRoot 'tests/run.ps1') -JavaHome $javaRoot -PythonExecutable $PythonExecutable}
