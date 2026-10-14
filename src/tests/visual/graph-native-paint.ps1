param([string]$Fixture,[string]$Report)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$paintSrc=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$paintBuild=Join-Path $paintSrc 'build'
$paintRoot=Join-Path $paintBuild 'native-graph-paint'
$paintJdk=Join-Path $paintBuild 'tools/graalvm-community-openjdk-25.0.2+10.1'
if(!$Fixture){$Fixture=Join-Path $paintBuild 'test-results/graph-physics-fixture.json'}
if(!$Report){$Report=Join-Path $paintBuild 'test-results/graph-native-paint.json'}
New-Item -ItemType Directory -Force -Path $paintRoot,(Join-Path $paintRoot 'classes'),(Join-Path $paintRoot 'native'),(Join-Path $paintRoot 'tmp') | Out-Null
$env:TEMP=Join-Path $paintRoot 'tmp';$env:TMP=$env:TEMP;$env:JAVA_HOME=$paintJdk
$paintVs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Tools/MSVC')}} | Select-Object -First 1
$paintMsvc=Get-ChildItem (Join-Path $paintVs.FullName 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$paintKits='C:/Program Files (x86)/Windows Kits/10'
$paintSdk=Get-ChildItem (Join-Path $paintKits 'Include') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'um/windows.h')} | Sort-Object Name -Descending | Select-Object -First 1
$env:PATH=(Join-Path $paintMsvc.FullName 'bin/Hostx64/x64')+';'+$env:PATH
$env:INCLUDE=@((Join-Path $paintMsvc.FullName 'include'),(Join-Path $paintSdk.FullName 'ucrt'),(Join-Path $paintSdk.FullName 'um'),(Join-Path $paintSdk.FullName 'shared')) -join ';'
$env:LIB=@((Join-Path $paintMsvc.FullName 'lib/x64'),(Join-Path $paintKits "Lib/$($paintSdk.Name)/ucrt/x64"),(Join-Path $paintKits "Lib/$($paintSdk.Name)/um/x64")) -join ';'
$paintJars=@((Get-ChildItem -LiteralPath (Join-Path $paintBuild 'runtime-libs') -Filter '*.jar' -File).FullName)
$paintClasspath=@((Join-Path $paintRoot 'classes'),(Join-Path $paintBuild 'classes/java/main'))+$paintJars
& (Join-Path $paintJdk 'bin/javac.exe') -encoding UTF-8 -cp ($paintClasspath -join ';') -d (Join-Path $paintRoot 'classes') (Join-Path $paintSrc 'modules/graph/tests/ai/enterprise/graph/GraphCanvasTest.java')
if($LASTEXITCODE -ne 0){throw 'Native graph paint test compilation failed'}
@{name='ai.enterprise.graph.GraphCanvas';allDeclaredFields=$true} | ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $paintRoot 'test-reflect.json') -Encoding utf8
$paintNativeClasspath=@((Join-Path $paintBuild 'build-logic-classes'))+$paintClasspath
$paintArguments=@('-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.windows.headers=ALL-UNNAMED',
    '--no-fallback','--enable-native-access=ALL-UNNAMED','-O2','-march=x86-64','-R:MaxHeapSize=1073741824','-H:+UnlockExperimentalVMOptions',
    '--features=ai.enterprise.build.NoFileLogFeature','--initialize-at-build-time=ai.enterprise.build.NoFileLogHandler',
    '--initialize-at-run-time=org.eclipse.swt,ai.enterprise.platform.WindowsPlatform',('--class-path='+($paintNativeClasspath -join ';')),
    ('-H:Path='+$paintRoot),'-H:Name=GraphCanvasProbe',('-H:JNIConfigurationFiles='+(Join-Path $paintBuild 'native-metadata/jni-config.json')),
    ('-H:ReflectionConfigurationFiles='+(Join-Path $paintBuild 'native-metadata/reflect-config.json')+','+(Join-Path $paintRoot 'test-reflect.json')),
    '-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS','-H:NativeLinkerOption=/ENTRY:mainCRTStartup','-J-Xmx4g','-J-XX:-UsePerfData','ai.enterprise.graph.GraphCanvasTest')
& (Join-Path $paintJdk 'bin/native-image.cmd') @paintArguments *> (Join-Path $paintRoot 'native-build.log')
if($LASTEXITCODE -ne 0){throw 'Native graph paint probe build failed; inspect src/build/native-graph-paint/native-build.log'}
foreach($name in @('swt-win32-4974r10.dll','swt-gdip-win32-4974r10.dll','swt-osversion-win32-4974r10.dll')){Copy-Item -LiteralPath (Join-Path $paintBuild ('native/'+$name)) -Destination (Join-Path $paintRoot 'native') -Force}
foreach($name in @('vcruntime140.dll','vcruntime140_1.dll')){Copy-Item -LiteralPath (Join-Path $paintBuild ('staging/EnterpriseAI/'+$name)) -Destination $paintRoot -Force}
$paintStart=[Diagnostics.ProcessStartInfo]::new((Join-Path $paintRoot 'GraphCanvasProbe.exe'))
$paintStart.UseShellExecute=$false;$paintStart.WindowStyle=[Diagnostics.ProcessWindowStyle]::Normal;$paintStart.RedirectStandardOutput=$true;$paintStart.RedirectStandardError=$true
foreach($argument in @((Join-Path $paintRoot 'native'),([IO.Path]::GetFullPath($Fixture)),([IO.Path]::GetFullPath($Report)))){$paintStart.ArgumentList.Add($argument)}
$paintProcess=[Diagnostics.Process]::Start($paintStart)
$paintStdout=$paintProcess.StandardOutput.ReadToEndAsync();$paintStderr=$paintProcess.StandardError.ReadToEndAsync()
if(!$paintProcess.WaitForExit(60000)){$paintProcess.Kill($true);throw 'Native graph paint test exceeded 60 seconds'}
$paintOutput=$paintStdout.GetAwaiter().GetResult();$paintErrors=$paintStderr.GetAwaiter().GetResult()
if($paintProcess.ExitCode -ne 0 -or $paintErrors){throw ('Native graph paint failed: '+$paintErrors+' '+$paintOutput)}
$paintResult=Get-Content -LiteralPath $Report -Raw|ConvertFrom-Json
$paintResult | Add-Member -NotePropertyName runtime -NotePropertyValue 'GraalVM Native Image 25.0.2 Windows x64' -Force
$paintResult | Add-Member -NotePropertyName source -NotePropertyValue 'Production GraphCanvas; test main and synthetic gestures only in developer probe' -Force
$paintResult.includes='SWT paint callback into RAM GDI buffer at the owned Canvas size; screen presentation and full application frame scheduling are separate'
$paintResult | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Report -Encoding utf8
Write-Output $paintOutput
