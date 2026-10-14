param([ValidateSet('patch-linker','stage')][string]$Action='patch-linker')
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent $PSScriptRoot
$buildRoot=Join-Path $sourceRoot 'build/static-toolchain'
$original=Join-Path $sourceRoot 'build/tools/graalvm-community-openjdk-25.0.2+10.1'
$target=Join-Path $buildRoot 'graalvm-static'
$upstream=Join-Path $buildRoot 'sources/graal/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/image/CCLinkerInvocation.java'
$patchRoot=Join-Path $buildRoot 'linker-patch'
$classes=Join-Path $patchRoot 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$text=Get-Content -LiteralPath $upstream -Raw
$old='// cmd.add("/MT");'+"`n"+'                    // Must use /MD in order to link with JDK native libraries built that way'+"`n"+'                    cmd.add("/MD");'
$text=$text.Replace("`r`n","`n")
if(!$text.Contains($old)){throw 'Pinned Windows executable linkage block does not match'}
$text=$text.Replace($old,'// EnterpriseAI: all linked runtime archives must use the static CRT.'+"`n"+'                    cmd.add("/MT");')
if(!$text.Contains('cmd.add("/NODEFAULTLIB:LIBCMT");')){throw 'Pinned CRT exclusion does not match'}
$text=$text.Replace('cmd.add("/NODEFAULTLIB:LIBCMT");','// EnterpriseAI: static CRT; no default-library suppression.')
$patched=Join-Path $patchRoot 'CCLinkerInvocation.java'
[IO.File]::WriteAllText($patched,$text,[Text.UTF8Encoding]::new($false))
$classpath=@(Get-ChildItem -LiteralPath (Join-Path $original 'lib/svm/builder') -Filter '*.jar' | ForEach-Object FullName)
$classpath+=@(Get-ChildItem -LiteralPath (Join-Path $original 'lib/svm') -Filter '*.jar' | ForEach-Object FullName)
& (Join-Path $original 'bin/javac.exe') '--add-modules=jdk.graal.compiler,jdk.internal.vm.ci' '--add-exports=jdk.graal.compiler/jdk.graal.compiler.options=ALL-UNNAMED' '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED' '-classpath' ($classpath -join ';') '-d' $classes $patched
if($LASTEXITCODE -ne 0){throw 'Pinned Windows linker patch compilation failed'}
if($Action -eq 'stage'){
    & robocopy.exe $original $target /E /NFL /NDL /NJH /NJS /NP
    if($LASTEXITCODE -gt 7){throw 'Isolated toolchain copy failed'}
    & (Join-Path $original 'bin/jar.exe') uf (Join-Path $target 'lib/svm/builder/svm.jar') '-C' $classes 'com/oracle/svm/hosted/image'
    if($LASTEXITCODE -ne 0){throw 'Staging patched SVM classes failed'}
    Write-Output "Staged isolated toolchain: $target (JDK archives must still be rebuilt /MT)."
}
Write-Output 'Pinned Windows executable linker compiled with /MT and without CRT suppression.'
