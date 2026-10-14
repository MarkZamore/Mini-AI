param([switch]$SkipJavaBuild,[string]$OutputDirectory='',[string]$SourceDirectory='')
$ErrorActionPreference='Stop'
$toolSourceRoot=Split-Path -Parent $PSScriptRoot
$sourceRoot=if($SourceDirectory){[IO.Path]::GetFullPath($SourceDirectory)}else{$toolSourceRoot}
$staticRoot=Join-Path $toolSourceRoot 'build/static-toolchain'
$jdk=Join-Path $staticRoot 'graalvm-static'
$native=Join-Path $staticRoot 'application-native'
if(!$OutputDirectory){$OutputDirectory=Join-Path $sourceRoot 'build/static-image'}
New-Item -ItemType Directory -Force -Path $OutputDirectory,(Join-Path $staticRoot 'tmp') | Out-Null
$msvc='C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231'
$sdk='C:/Program Files (x86)/Windows Kits/10';$sdkVersion='10.0.26100.0'
$env:PATH="$msvc/bin/Hostx64/x64;"+$env:PATH
$env:INCLUDE=@("$msvc/include","$sdk/Include/$sdkVersion/ucrt","$sdk/Include/$sdkVersion/um","$sdk/Include/$sdkVersion/shared") -join ';'
$env:LIB=@("$msvc/lib/x64","$sdk/Lib/$sdkVersion/ucrt/x64","$sdk/Lib/$sdkVersion/um/x64") -join ';'
$env:JAVA_HOME=$jdk;$env:VSLANG='1033';$env:TEMP=Join-Path $staticRoot 'tmp';$env:TMP=$env:TEMP
$env:ENTERPRISE_BUILD_SHARED_ROOT=Join-Path $toolSourceRoot 'build'
if(!$env:ENTERPRISE_BUILD_PYTHON){
    $bundledPython=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
    $env:ENTERPRISE_BUILD_PYTHON=if(Test-Path -LiteralPath $bundledPython){$bundledPython}else{'python.exe'}
}
function Check([string]$Step){if($LASTEXITCODE -ne 0){throw "$Step failed ($LASTEXITCODE)"}}
Push-Location -LiteralPath $sourceRoot
try {
    $toolchainArchives=@()
    foreach($archive in @('java.lib','net.lib','nio.lib','zip.lib','extnet.lib','sunmscapi.lib')) {
        $path=Join-Path $jdk "lib/static/windows-amd64/$archive"
        $directives=(& dumpbin.exe /directives $path) -join "`n";Check "COFF $archive"
        if($directives -match '(?i)DEFAULTLIB:["'']?(MSVCRT[D]?|MSVCPRT[D]?)\b'){throw "Dynamic CRT remains in toolchain archive: $archive"}
        $toolchainArchives+=@{name=$archive;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant();crt='/MT'}
    }
    foreach($archive in @('jvm.lib','libchelper.lib')) {
        $path=Join-Path $jdk "lib/svm/clibraries/windows-amd64/$archive"
        $directives=(& dumpbin.exe /directives $path) -join "`n";Check "COFF $archive"
        if($directives -match '(?i)DEFAULTLIB:["'']?(MSVCRT[D]?|MSVCPRT[D]?)\b'){throw "Dynamic CRT remains in SVM archive: $archive"}
        $toolchainArchives+=@{name=$archive;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant();crt='/MT'}
    }
    if(!$SkipJavaBuild){
        & (Join-Path $toolSourceRoot 'build/tools/gradle-9.8.1/bin/gradle.bat') --no-daemon --project-cache-dir build/gradle-project-cache --gradle-user-home (Join-Path $toolSourceRoot 'build/gradle-home') jar copyRuntime
        Check 'Application Java build'
    }
    $cp=(@((Join-Path $sourceRoot 'build/libs/EnterpriseAI-1.0.0.jar'))+@(Get-ChildItem (Join-Path $sourceRoot 'build/runtime-libs') -Filter '*.jar' | ForEach-Object FullName)) -join ';'
    $classes=Join-Path $OutputDirectory 'build-logic-classes';$metadata=Join-Path $OutputDirectory 'metadata'
    New-Item -ItemType Directory -Force -Path $classes,$metadata | Out-Null
    $svm=Join-Path $jdk 'lib/svm/builder/svm.jar'
    $helpers=@('NativeMetadata.java','NoFileLogFeature.java','NoFileLogHandler.java','StaticLibrariesFeature.java','SwtStaticLoaderSubstitution.java','WindowsExecutablePathSubstitution.java') | ForEach-Object {Join-Path $toolSourceRoot "build-logic/$_"}
    & (Join-Path $jdk 'bin/javac.exe') -classpath "$cp;$svm" -d $classes @helpers;Check 'Static build helpers'
    & (Join-Path $jdk 'bin/java.exe') -classpath "$classes;$cp" NativeMetadata (Join-Path $toolSourceRoot 'build/dependencies/swt-3.135.0-patched.jar') $metadata;Check 'Static JNI metadata'
    $inventory=Get-Content -LiteralPath (Join-Path $native 'inventory.json') -Raw | ConvertFrom-Json
    $selected=@($inventory | Where-Object {$_.name -ne 'enterpriseai_sqlite' -or (Test-Path -LiteralPath (Join-Path $sourceRoot 'modules/database/module.json'))})
    $arguments=@('--no-fallback','--verbose','--enable-native-access=ALL-UNNAMED','-O2','-march=x86-64','-R:MaxHeapSize=1073741824','-H:+UnlockExperimentalVMOptions','--features=ai.enterprise.build.NoFileLogFeature,ai.enterprise.build.StaticLibrariesFeature','--initialize-at-build-time=ai.enterprise.build.NoFileLogHandler','--initialize-at-run-time=org.eclipse.swt,ai.enterprise.platform.WindowsPlatform',"--class-path=$classes;$cp","-H:Path=$OutputDirectory",'-H:Name=EnterpriseAI',"-H:JNIConfigurationFiles=$metadata/jni-config.json","-H:ReflectionConfigurationFiles=$metadata/reflect-config.json","-H:IncludeResources=enterpriseai/legal/.*","-H:CLibraryPath=$native",'-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS','-H:NativeLinkerOption=/ENTRY:mainCRTStartup','-J-Xmx8g','-J-XX:-UsePerfData')
    $exports=@('EXPORTS')
    foreach($entry in $selected){
        if((Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256){throw "Static archive changed: $($entry.name)"}
        $directives=(& dumpbin.exe /directives $entry.path) -join "`n";Check "COFF $($entry.name)"
        if($directives -match '(?i)DEFAULTLIB:["'']?(MSVCRT[D]?|MSVCPRT[D]?)\b'){throw "Dynamic CRT remains in application archive: $($entry.name)"}
        $arguments+="-H:NativeLinkerOption=/WHOLEARCHIVE:$($entry.path)"
        foreach($symbol in $entry.exports){$exports+='  '+$symbol}
    }
    $exportFile=Join-Path $OutputDirectory 'builtin-jni.def'
    $exports | Set-Content -LiteralPath $exportFile -Encoding ASCII
    $arguments+="-H:NativeLinkerOption=/DEF:$exportFile"
    foreach($library in @('shell32','ole32','imm32','oleaut32','oleacc','comctl32','comdlg32','gdi32','user32','usp10','gdiplus','winspool','uuid','shlwapi','msimg32','urlmon','propsys','wininet','uxtheme','dwmapi')){$arguments+="-H:NativeLinkerOption=$library.lib"}
    $arguments+="-H:NativeLinkerOption=$native/swt-icons.res"
    $arguments+='-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.windows.headers=ALL-UNNAMED'
    $arguments+='-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED'
    $arguments+='ai.enterprise.bootstrap.Main'
    $sourceFiles=@(Get-ChildItem -LiteralPath $sourceRoot -File)+@(Get-ChildItem -LiteralPath $sourceRoot -Directory | Where-Object {$_.Name -notin @('build','.git','.gradle')} | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -File -Recurse})
    $sourceSnapshot=@($sourceFiles | Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' } | Sort-Object FullName | ForEach-Object {@{path=$_.FullName.Substring($sourceRoot.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
    $jarSnapshot=(Get-FileHash -LiteralPath (Join-Path $sourceRoot 'build/libs/EnterpriseAI-1.0.0.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    $argumentFile=Join-Path $OutputDirectory 'native-image.args'
    $arguments | ForEach-Object {'"'+$_.Replace('\','\\').Replace('"','\"')+'"'} | Set-Content -LiteralPath $argumentFile -Encoding ASCII
    & (Join-Path $jdk 'bin/native-image.cmd') ('@'+$argumentFile) *> (Join-Path $OutputDirectory 'native-image.log');Check 'Static Native Image'
    if(Select-String -LiteralPath (Join-Path $OutputDirectory 'native-image.log') -Pattern 'LNK4098|LNK4006|LNK4217|LNK4049' -Quiet){throw 'Conflicting CRT or duplicate native symbols in link report'}
    $manifest=Join-Path $OutputDirectory 'EnterpriseAI.manifest'
    @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity version="1.0.0.0" processorArchitecture="amd64" name="EnterpriseAI" type="win32"/>
  <dependency><dependentAssembly><assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0" processorArchitecture="amd64" publicKeyToken="6595b64144ccf1df" language="*"/></dependentAssembly></dependency>
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges><requestedExecutionLevel level="asInvoker" uiAccess="false"/></requestedPrivileges></security></trustInfo>
  <application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings><longPathAware xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">true</longPathAware></windowsSettings></application>
</assembly>
'@ | Set-Content -LiteralPath $manifest -Encoding UTF8
    & (Join-Path $sdk "bin/$sdkVersion/x64/mt.exe") -nologo -manifest $manifest ("-outputresource:"+(Join-Path $OutputDirectory 'EnterpriseAI.exe')+';#1');Check 'EXE manifest'
    $imports=(& dumpbin.exe /imports (Join-Path $OutputDirectory 'EnterpriseAI.exe')) -join "`n";Check 'Import inspection'
    $imports | Set-Content -LiteralPath (Join-Path $OutputDirectory 'imports.txt') -Encoding UTF8
    if($imports -match '(?i)(VCRUNTIME|MSVCP|MSVCR|LIBOMP|JVM|JAVA)\w*\.dll'){throw 'Non-inbox runtime DLL import remains'}
    $selected | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'static-library-inventory.json') -Encoding UTF8
    @{schemaVersion=1;profile='windows-x64-static';executableSha256=(Get-FileHash -LiteralPath (Join-Path $OutputDirectory 'EnterpriseAI.exe') -Algorithm SHA256).Hash.ToLowerInvariant();applicationJarSha256=$jarSnapshot;toolchainArchives=$toolchainArchives;applicationArchives=$selected;sources=$sourceSnapshot} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'build-manifest.json') -Encoding UTF8
    Write-Output (Join-Path $OutputDirectory 'EnterpriseAI.exe')
} finally {Pop-Location}
