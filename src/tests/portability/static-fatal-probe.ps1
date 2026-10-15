param()
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$static=Join-Path $sourceRoot 'build/static-toolchain'
$jdk=Join-Path $static 'graalvm-static'
$probe=Join-Path $sourceRoot 'build/test-results/static-fatal-probe'
$classes=Join-Path $probe 'classes'
New-Item -ItemType Directory -Force -Path $probe,$classes,(Join-Path $probe 'temporary') | Out-Null
$msvc='C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231'
$sdk='C:/Program Files (x86)/Windows Kits/10';$sdkVersion='10.0.26100.0'
$env:PATH="$msvc/bin/Hostx64/x64;"+$env:PATH
$env:INCLUDE=@("$msvc/include","$sdk/Include/$sdkVersion/ucrt","$sdk/Include/$sdkVersion/um","$sdk/Include/$sdkVersion/shared") -join ';'
$env:LIB=@("$msvc/lib/x64","$sdk/Lib/$sdkVersion/ucrt/x64","$sdk/Lib/$sdkVersion/um/x64") -join ';'
$env:TEMP=Join-Path $probe 'temporary';$env:TMP=$env:TEMP;$env:VSLANG='1033'
function Check([string]$Step){if($LASTEXITCODE -ne 0){throw "$Step failed ($LASTEXITCODE)"}}
$java=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'CrashProbe.java'))
$java=$java.Replace('System.load(Path.of(args[0]).toAbsolutePath().toString());','System.loadLibrary("enterpriseai_fatalprobe");')
$java=$java.Replace('args.length>1&&args[1].equals("oom")','args.length>0&&args[args.length-1].equals("oom")')
[IO.File]::WriteAllText((Join-Path $probe 'CrashProbe.java'),$java,[Text.UTF8Encoding]::new($false))
@'
import com.oracle.svm.core.jdk.NativeLibrarySupport;
import org.graalvm.nativeimage.hosted.Feature;
public final class FatalStaticFeature implements Feature {
    public void duringSetup(DuringSetupAccess access) {
        NativeLibrarySupport.singleton().preregisterUninitializedBuiltinLibrary("enterpriseai_fatalprobe");
    }
}
'@ | Set-Content -LiteralPath (Join-Path $probe 'FatalStaticFeature.java') -Encoding UTF8
@'
#include <jni.h>
JNIEXPORT jint JNICALL JNI_OnLoad_enterpriseai_fatalprobe(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved; return JNI_VERSION_1_8;
}
'@ | Set-Content -LiteralPath (Join-Path $probe 'onload.c') -Encoding UTF8
& cl.exe /nologo /c /O2 /MT ("/I$jdk/include") ("/I$jdk/include/win32") (Join-Path $PSScriptRoot 'fatal_probe.c') ("/Fo$probe/fatal.obj");Check 'Static fatal JNI'
& cl.exe /nologo /c /O2 /MT ("/I$jdk/include") ("/I$jdk/include/win32") (Join-Path $probe 'onload.c') ("/Fo$probe/onload.obj");Check 'Static fatal initializer'
& lib.exe /nologo ("/OUT:$probe/enterpriseai_fatalprobe.lib") (Join-Path $probe 'fatal.obj') (Join-Path $probe 'onload.obj');Check 'Static fatal archive'
$svm=Join-Path $jdk 'lib/svm/builder/svm.jar'
& (Join-Path $jdk 'bin/javac.exe') '--add-modules=org.graalvm.nativeimage' -classpath $svm -d $classes (Join-Path $probe 'CrashProbe.java') (Join-Path $probe 'FatalStaticFeature.java') (Join-Path $sourceRoot 'build-logic/NoFileLogFeature.java') (Join-Path $sourceRoot 'build-logic/NoFileLogHandler.java');Check 'Static fatal Java helpers'
$options=@('--no-fallback','--enable-native-access=ALL-UNNAMED','-O1','-march=x86-64','-R:MaxHeapSize=33554432','--parallelism=4','--features=ai.enterprise.build.NoFileLogFeature,FatalStaticFeature','--initialize-at-build-time=ai.enterprise.build.NoFileLogHandler',"--class-path=$classes","-H:CLibraryPath=$probe","-H:NativeLinkerOption=/WHOLEARCHIVE:$probe/enterpriseai_fatalprobe.lib",'-o',(Join-Path $probe 'CrashProbe'),'-J-Xmx2g','-J-XX:-UsePerfData','CrashProbe')
$options=@('-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED')+$options
& (Join-Path $jdk 'bin/native-image.cmd') @options *> (Join-Path $probe 'native-image.log');Check 'Static fatal Native Image'
$imports=(& dumpbin.exe /imports (Join-Path $probe 'CrashProbe.exe')) -join "`n";Check 'Static fatal imports'
if($imports -match '(?i)(VCRUNTIME|MSVCP|MSVCR|JVM|JAVA|LIBOMP)\w*\.dll'){throw 'Additional runtime import in fatal probe'}
$before=@(Get-ChildItem -LiteralPath $probe -Recurse -File | ForEach-Object { $_.FullName+':'+$_.Length+':'+$_.LastWriteTimeUtc.Ticks })
$cases=@()
foreach($mode in @('fatal','oom')){
    $start=[Diagnostics.ProcessStartInfo]::new((Join-Path $probe 'CrashProbe.exe'))
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.WorkingDirectory=$env:SystemRoot;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.ArgumentList.Add($mode)
    $child=[Diagnostics.Process]::Start($start);$out=$child.StandardOutput.ReadToEndAsync();$err=$child.StandardError.ReadToEndAsync()
    if(!$child.WaitForExit(15000)){$child.Kill($true);throw 'Fatal probe hung'}
    if(($mode -eq 'fatal' -and $child.ExitCode -eq 0) -or ($mode -eq 'oom' -and $child.ExitCode -ne 0)){throw 'Unexpected fatal/OOM exit code'}
    $cases+=@{mode=$mode;exitCode=$child.ExitCode;stdout=$out.GetAwaiter().GetResult();stderr=$err.GetAwaiter().GetResult()};$child.Dispose()
}
$after=@(Get-ChildItem -LiteralPath $probe -Recurse -File | ForEach-Object { $_.FullName+':'+$_.Length+':'+$_.LastWriteTimeUtc.Ticks })
if(Compare-Object $before $after){throw 'Static fatal/OOM probe changed files'}
@{completed=$true;exeSha256=(Get-FileHash -LiteralPath (Join-Path $probe 'CrashProbe.exe')).Hash.ToLowerInvariant();cases=$cases;filesUnchanged=$true;scope='Standalone static development probe, actual JNI FatalError + caught OOM, same production NoFileLogFeature/Handler';limitations=@('File snapshots do not verify transient writes or registry operations; no blanket operating-system guarantee')}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $sourceRoot 'build/test-results/static-fatal-probe.json') -Encoding UTF8
Write-Output 'Static JNI FatalError and caught OOM passed; no probe files changed.'
