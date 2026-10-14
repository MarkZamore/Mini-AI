param([ValidateSet('build','run')][string]$Action='build')
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent $PSScriptRoot
$buildRoot=Join-Path $sourceRoot 'build/static-toolchain'
$jdk=Join-Path $buildRoot 'graalvm-static'
$gate=Join-Path $buildRoot 'runtime-gate'
$classes=Join-Path $gate 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$msvc='C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231'
$sdk='C:/Program Files (x86)/Windows Kits/10'
$sdkVersion='10.0.26100.0'
$env:PATH="$msvc/bin/Hostx64/x64;"+$env:PATH
$env:INCLUDE=@("$msvc/include","$sdk/Include/$sdkVersion/ucrt","$sdk/Include/$sdkVersion/um","$sdk/Include/$sdkVersion/shared") -join ';'
$env:LIB=@("$msvc/lib/x64","$sdk/Lib/$sdkVersion/ucrt/x64","$sdk/Lib/$sdkVersion/um/x64") -join ';'
$env:VSLANG='1033'
$env:TEMP=Join-Path $buildRoot 'tmp'
$env:TMP=$env:TEMP
if($Action -eq 'build'){
    $compiled=Join-Path $buildRoot 'sources/labs-openjdk/build/enterpriseai-static/support/native'
    $archives=@(Get-ChildItem -LiteralPath $compiled -Filter '*.lib' -Recurse | Where-Object {$_.FullName -match '[\\/]static[\\/]'})
    if(!$archives){throw 'No rebuilt /MT Java static archives found; run build_jdk_static.sh first.'}
    $required=@('java.lib','net.lib','nio.lib','zip.lib','extnet.lib','sunmscapi.lib')
    foreach($name in $required){if(!($archives | Where-Object Name -eq $name)){throw "Missing rebuilt archive: $name"}}
    $inventory=@()
    foreach($archive in $archives){
        $directives=(& dumpbin.exe /directives $archive.FullName) -join "`n"
        if($LASTEXITCODE -ne 0){throw "COFF audit failed: $($archive.Name)"}
        if($directives -match '(?i)DEFAULTLIB:["'']?(MSVCRTD?|MSVCPRTD?)\b'){throw "Dynamic CRT in $($archive.Name)"}
        Copy-Item -LiteralPath $archive.FullName -Destination (Join-Path $jdk "lib/static/windows-amd64/$($archive.Name)") -Force
        $inventory+=@{name=$archive.Name;sha256=(Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant();crt='/MT'}
    }
    $inventory | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $gate 'archive-inventory.json') -Encoding UTF8
    & (Join-Path $jdk 'bin/javac.exe') '--add-modules=org.graalvm.nativeimage' '-d' $classes (Join-Path $PSScriptRoot 'StaticRuntimeProbe.java') (Join-Path $sourceRoot 'build-logic/NoFileLogFeature.java') (Join-Path $sourceRoot 'build-logic/NoFileLogHandler.java')
    if($LASTEXITCODE -ne 0){throw 'Static runtime probe compilation failed'}
    $arguments=@('--no-fallback','--verbose','--enable-native-access=ALL-UNNAMED','-O1','-march=x86-64','-R:MaxHeapSize=268435456','--features=ai.enterprise.build.NoFileLogFeature','--initialize-at-build-time=ai.enterprise.build.NoFileLogHandler',('--class-path='+$classes),('-H:Path='+$gate),'-H:Name=StaticRuntimeProbe','-J-Xmx4g','-J-XX:-UsePerfData','StaticRuntimeProbe')
    & (Join-Path $jdk 'bin/native-image.cmd') @arguments *> (Join-Path $gate 'native-image.log')
    if($LASTEXITCODE -ne 0){throw "Static runtime probe linking failed: $gate/native-image.log"}
    $imports=(& dumpbin.exe /imports (Join-Path $gate 'StaticRuntimeProbe.exe')) -join "`n"
    $imports | Set-Content -LiteralPath (Join-Path $gate 'imports.txt') -Encoding UTF8
    if($imports -match '(?i)(VCRUNTIME|MSVCP|MSVCR|LIBOMP|JVM|JAVA)\w*\.dll'){throw 'Non-inbox runtime DLL import remains'}
    Write-Output 'Static runtime probe linked. PE imports need whitelist and live-module acceptance.'
}else{
    & (Join-Path $gate 'StaticRuntimeProbe.exe') (Join-Path $PSScriptRoot 'static-sources.json')
    if($LASTEXITCODE -ne 0){throw 'Static runtime probe failed'}
}
