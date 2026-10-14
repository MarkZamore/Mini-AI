param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$probeRoot=Join-Path $sourceRoot 'build/test-results/styledtext-probe'
$classes=Join-Path $probeRoot 'classes'
New-Item -ItemType Directory -Force -Path $probeRoot,$classes,(Join-Path $probeRoot 'temporary')|Out-Null
$env:TEMP=Join-Path $probeRoot 'temporary';$env:TMP=$env:TEMP
$vs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Tools/MSVC')}} | Select-Object -First 1
$msvc=Get-ChildItem (Join-Path $vs.FullName 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$kits='C:/Program Files (x86)/Windows Kits/10'
$sdk=Get-ChildItem (Join-Path $kits 'Include') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'um/windows.h')} | Sort-Object Name -Descending | Select-Object -First 1
$env:INCLUDE=@((Join-Path $msvc.FullName 'include'),(Join-Path $sdk.FullName 'ucrt'),(Join-Path $sdk.FullName 'um'),(Join-Path $sdk.FullName 'shared'))-join ';'
$env:LIB=@((Join-Path $msvc.FullName 'lib/x64'),(Join-Path $kits "Lib/$($sdk.Name)/ucrt/x64"),(Join-Path $kits "Lib/$($sdk.Name)/um/x64"))-join ';'
$env:PATH=(Join-Path $msvc.FullName 'bin/Hostx64/x64')+';'+$env:PATH
$helper=Join-Path $sourceRoot 'build/build-logic-classes'
$libraries=(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'build/runtime-libs') -Filter '*.jar').FullName
$classpath=@($helper,$classes,(Join-Path $sourceRoot 'build/libs/EnterpriseAI-1.0.0.jar'))+$libraries -join ';'
& (Join-Path $JavaHome 'bin/javac.exe') -classpath $classpath -d $classes (Join-Path $PSScriptRoot 'StyledTextLongPathProbe.java');if($LASTEXITCODE -ne 0){throw 'Probe Java compilation failed'}
$metadata=Join-Path $sourceRoot 'build/native-metadata'
$options=@('-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.windows.headers=ALL-UNNAMED','--no-fallback','--enable-native-access=ALL-UNNAMED','-O1','-march=x86-64','--parallelism=2','--features=ai.enterprise.build.NoFileLogFeature','--initialize-at-build-time=ai.enterprise.build.NoFileLogHandler','--initialize-at-run-time=org.eclipse.swt','--class-path',$classpath,'-H:+UnlockExperimentalVMOptions',('-H:JNIConfigurationFiles='+(Join-Path $metadata 'jni-config.json')),('-H:ReflectionConfigurationFiles='+(Join-Path $metadata 'reflect-config.json')),'-o',(Join-Path $probeRoot 'StyledTextLongPathProbe'),'-J-Xmx2g','-J-XX:-UsePerfData','ai.enterprise.tests.StyledTextLongPathProbe')
& (Join-Path $JavaHome 'bin/native-image.cmd') @options;if($LASTEXITCODE -ne 0){throw 'StyledText native probe compilation failed'}
