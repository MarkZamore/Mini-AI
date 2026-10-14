param(
    [int[]]$Masks=@(0,118,126,125,123,119,111,95,63),
    [switch]$IncludeFull,
    [string]$PythonExecutable=$env:ENTERPRISE_TEST_PYTHON,
    [string]$GuiGate
)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$variantSrc=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$variantBuild=Join-Path $variantSrc 'build'
$variantRoot=Join-Path $variantBuild 'native-variants'
$variantJdk=Join-Path $variantBuild 'tools/graalvm-community-openjdk-25.0.2+10.1'
$variantIds=@('assistant','database','documents','graph','inference','search','sources')
if(!$PythonExecutable){$variantPythonCommand=Get-Command python -ErrorAction SilentlyContinue;if($variantPythonCommand){$PythonExecutable=$variantPythonCommand.Source}}
if(!$PythonExecutable -or !(Test-Path -LiteralPath $PythonExecutable)){throw 'Developer-only Python is needed for explicit Windows long-path process launch; it is not an application dependency.'}
New-Item -ItemType Directory -Force -Path $variantRoot,(Join-Path $variantRoot 'inputs'),(Join-Path $variantRoot 'tmp') | Out-Null
$env:TEMP=Join-Path $variantRoot 'tmp'
$env:TMP=$env:TEMP
$env:JAVA_HOME=$variantJdk

function Assert-Variant([bool]$Condition,[string]$Message) { if(!$Condition){throw $Message} }
function Reset-Variant([string]$Path) {
    $resolvedTarget=[IO.Path]::GetFullPath($Path)
    Assert-Variant ($resolvedTarget.StartsWith($variantRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) ('Unsafe variant path: '+$resolvedTarget)
    if(Test-Path -LiteralPath $resolvedTarget){
        $actualTarget=(Resolve-Path -LiteralPath $resolvedTarget).Path
        Assert-Variant ($actualTarget.StartsWith($variantRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'Variant deletion resolves outside test workspace'
        Remove-Item -LiteralPath $actualTarget -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $resolvedTarget | Out-Null
}
function Set-VariantCompiler {
    $variantVs=Get-ChildItem 'C:/Program Files/Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | ForEach-Object {Get-ChildItem -LiteralPath $_.FullName -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'VC/Tools/MSVC')}} | Select-Object -First 1
    Assert-Variant ($null -ne $variantVs) 'MSVC is required to compile native test variants'
    $variantMsvc=Get-ChildItem (Join-Path $variantVs.FullName 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
    $variantKits='C:/Program Files (x86)/Windows Kits/10'
    $variantSdk=Get-ChildItem (Join-Path $variantKits 'Include') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'um/windows.h')} | Sort-Object Name -Descending | Select-Object -First 1
    Assert-Variant ($null -ne $variantSdk) 'Windows SDK is required to compile native test variants'
    $env:PATH=(Join-Path $variantMsvc.FullName 'bin/Hostx64/x64')+';'+$env:PATH
    $env:INCLUDE=@((Join-Path $variantMsvc.FullName 'include'),(Join-Path $variantSdk.FullName 'ucrt'),(Join-Path $variantSdk.FullName 'um'),(Join-Path $variantSdk.FullName 'shared')) -join ';'
    $env:LIB=@((Join-Path $variantMsvc.FullName 'lib/x64'),(Join-Path $variantKits "Lib/$($variantSdk.Name)/ucrt/x64"),(Join-Path $variantKits "Lib/$($variantSdk.Name)/um/x64")) -join ';'
}
function Copy-VariantResources([string]$ResourceRoot,[string]$Destination) {
    if(!(Test-Path -LiteralPath $ResourceRoot)){return}
    foreach($resource in Get-ChildItem -LiteralPath $ResourceRoot -Recurse -File){
        $relative=[IO.Path]::GetRelativePath($ResourceRoot,$resource.FullName)
        $target=Join-Path $Destination $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath $resource.FullName -Destination $target -Force
    }
}
function Invoke-Variant([string]$Exe,[string]$Command,[int]$TimeoutSeconds=30,[int]$ExpectedExitCode=0) {
    if($Command -eq 'ui-smoke' -and $GuiGate -and !(Test-Path -LiteralPath $GuiGate)){
        Write-Host 'Native UI probe paused at the observer GUI gate; compilation and CLI checks are complete.'
        $variantGuiWait=[Diagnostics.Stopwatch]::StartNew()
        while(!(Test-Path -LiteralPath $GuiGate)){
            if($variantGuiWait.Elapsed.TotalMinutes -ge 15){throw 'Observer GUI gate timed out; no application window was opened'}
            Start-Sleep -Milliseconds 500
        }
    }
    $variantLaunchOutput=& $PythonExecutable -B (Join-Path $PSScriptRoot 'native_launch.py') $Exe $Command $TimeoutSeconds $ExpectedExitCode $env:SystemRoot
    Assert-Variant ($LASTEXITCODE -eq 0) ('Variant '+$Command+' launch/assertions failed: '+$Exe)
    return (($variantLaunchOutput -join "`n") | ConvertFrom-Json)
}
function Get-NormalVariantPath([string]$Value) {
    $valueFull=[IO.Path]::GetFullPath($Value)
    if($valueFull.StartsWith('\\?\UNC\')){return '\\'+$valueFull.Substring(8)}
    if($valueFull.StartsWith('\\?\')){return $valueFull.Substring(4)}
    return $valueFull
}
function Get-VariantInventory([string]$Root) {
    $items=@(foreach($item in Get-ChildItem -LiteralPath $Root -Force -Recurse){
        $relative=[IO.Path]::GetRelativePath($Root,$item.FullName)
        if($item.PSIsContainer){'D|'+$relative}
        else{'F|'+$relative+'|'+$item.Length+'|'+$item.LastWriteTimeUtc.Ticks+'|'+(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash}
    })
    return (($items | Sort-Object) -join "`n")
}

Set-VariantCompiler
$variantInputs=Join-Path $variantRoot 'inputs'
Reset-Variant $variantInputs
Copy-Item -LiteralPath (Join-Path $variantBuild 'build-logic-classes') -Destination (Join-Path $variantInputs 'helpers') -Recurse
Copy-Item -LiteralPath (Join-Path $variantBuild 'native-metadata') -Destination (Join-Path $variantInputs 'metadata') -Recurse
Copy-Item -LiteralPath (Join-Path $variantBuild 'native') -Destination (Join-Path $variantInputs 'native') -Recurse
Copy-Item -LiteralPath (Join-Path $variantBuild 'module-removal/dependencies') -Destination (Join-Path $variantInputs 'dependencies') -Recurse
$variantCrtNames=@('vcruntime140.dll','vcruntime140_1.dll')
$variantCrtInput=Join-Path $variantInputs 'crt'
New-Item -ItemType Directory -Force -Path $variantCrtInput | Out-Null
foreach($crt in $variantCrtNames){
    $publishedCrt=Join-Path $variantBuild ('staging/EnterpriseAI/'+$crt)
    Assert-Variant (Test-Path -LiteralPath $publishedCrt) ('Missing licensed app-local CRT in final staging: '+$crt)
    Copy-Item -LiteralPath $publishedCrt -Destination $variantCrtInput
}
$variantJars=@((Get-ChildItem (Join-Path $variantInputs 'dependencies') -Filter '*.jar' -File).FullName)
Assert-Variant ($variantJars.Count -ge 3) 'Run the complete ModuleRemovalTest before the native variants'
$variantReport=[Collections.Generic.List[object]]::new()
$variantRuntimeTasks=[Collections.Generic.List[object]]::new()
$variantPassed=0
$variantFailure=$null
$variantStarted=[Diagnostics.Stopwatch]::StartNew()
if($IncludeFull){$Masks=@($Masks)+@(127)}

try {
    foreach($mask in @($Masks | Select-Object -Unique)){
        Assert-Variant ($mask -ge 0 -and $mask -le 127) ('Invalid mask '+$mask)
        $caseName='mask-'+$mask.ToString('000')
        $matrixCase=Join-Path $variantBuild ('module-removal/'+$caseName)
        Assert-Variant (Test-Path -LiteralPath (Join-Path $matrixCase 'classes/ai/enterprise/generated/GeneratedCatalog.class')) ('Missing compiled module case '+$caseName)
        $caseRoot=Join-Path $variantRoot $caseName
        Reset-Variant $caseRoot
        $caseClasses=Join-Path $caseRoot 'classes'
        Copy-Item -LiteralPath (Join-Path $matrixCase 'classes') -Destination $caseClasses -Recurse
        $selected=@(for($i=0;$i -lt $variantIds.Count;$i++){if($mask -band (1 -shl $i)){$variantIds[$i]}})
        foreach($core in Get-ChildItem (Join-Path $variantSrc 'core') -Directory){Copy-VariantResources (Join-Path $core.FullName 'resources') $caseClasses}
        foreach($id in $selected){Copy-VariantResources (Join-Path $variantSrc ('modules/'+$id+'/resources')) $caseClasses}
        $imageRoot=Join-Path $caseRoot 'app'
        New-Item -ItemType Directory -Force -Path $imageRoot | Out-Null
        $classpath=@((Join-Path $variantInputs 'helpers'),$caseClasses)+$variantJars
        $nativeArguments=@('-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.windows.headers=ALL-UNNAMED',
            '--no-fallback','--enable-native-access=ALL-UNNAMED','-O2','-march=x86-64','-R:MaxHeapSize=1073741824','-H:+UnlockExperimentalVMOptions',
            '--features=ai.enterprise.build.NoFileLogFeature','--initialize-at-build-time=ai.enterprise.build.NoFileLogHandler',
            '--initialize-at-run-time=org.eclipse.swt,ai.enterprise.platform.WindowsPlatform',('--class-path='+($classpath -join ';')),
            ('-H:Path='+$imageRoot),'-H:Name=EnterpriseAI',('-H:JNIConfigurationFiles='+(Join-Path $variantInputs 'metadata/jni-config.json')),
            ('-H:ReflectionConfigurationFiles='+(Join-Path $variantInputs 'metadata/reflect-config.json')),
            '-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS','-H:NativeLinkerOption=/ENTRY:mainCRTStartup','-J-Xmx8g','-J-XX:-UsePerfData','ai.enterprise.bootstrap.Main')
        Write-Output ('Building native '+$caseName+' ['+($selected -join ', ')+']')
        $caseTimer=[Diagnostics.Stopwatch]::StartNew()
        & (Join-Path $variantJdk 'bin/native-image.cmd') @nativeArguments *> (Join-Path $caseRoot 'native-build.log')
        Assert-Variant ($LASTEXITCODE -eq 0) ('Native image compilation failed for '+$caseName+'; inspect '+(Join-Path $caseRoot 'native-build.log'))
        $caseNative=Join-Path $imageRoot 'native'
        New-Item -ItemType Directory -Force -Path $caseNative | Out-Null
        $nativeNames=@('enterpriseai-platform.dll','swt-win32-4974r10.dll','swt-gdip-win32-4974r10.dll','swt-osversion-win32-4974r10.dll')
        if($selected -contains 'database'){$nativeNames+=@('enterpriseai-sqlite.dll')}
        foreach($dll in $nativeNames){Copy-Item -LiteralPath (Join-Path $variantInputs ('native/'+$dll)) -Destination $caseNative}
        foreach($crt in $variantCrtNames){Copy-Item -LiteralPath (Join-Path $variantCrtInput $crt) -Destination $imageRoot}
        $exe=Join-Path $imageRoot 'EnterpriseAI.exe'
        $status=Invoke-Variant $exe 'status'
        $actualIds=@($status.modules | Sort-Object)
        $expectedIds=@($selected | Sort-Object)
        Assert-Variant (($actualIds -join ',') -eq ($expectedIds -join ',')) ('Native module list mismatch '+$caseName)
        $expectedPanels=@($selected | Where-Object {$_ -in @('assistant','graph')} | Sort-Object)
        Assert-Variant ((@($status.panels | Sort-Object) -join ',') -eq ($expectedPanels -join ',')) ('Native pane list mismatch '+$caseName)
        Assert-Variant ((Get-NormalVariantPath $status.root) -eq (Get-NormalVariantPath $imageRoot)) ('Native root wrongly follows working directory '+$caseName)
        Assert-Variant (!$status.historyPersistence) ('History persistence unexpectedly enabled '+$caseName)
        $expectedCommands=@()
        if($selected -contains 'database'){$expectedCommands+=@('database-info')}
        if($selected -contains 'documents' -and $selected -contains 'database'){$expectedCommands+=@('catalog','document','canonical','resolve')}
        if($selected -contains 'sources' -and $selected -contains 'documents' -and $selected -contains 'database'){$expectedCommands+=@('open','open-source')}
        if($selected -contains 'search'){$expectedCommands+=@('search','get-fragment')}
        if($selected -contains 'graph'){$expectedCommands+=@('graph','neighbors')}
        if($selected -contains 'inference'){$expectedCommands+=@('model-status')}
        Assert-Variant ((@($status.commands | Sort-Object) -join ',') -eq (@($expectedCommands | Sort-Object) -join ',')) ('Native commands mismatch '+$caseName)
        $unicodeCommand='проверка-қазақ-😀'
        $unicodeResult=Invoke-Variant $exe $unicodeCommand 30 1
        Assert-Variant (!$unicodeResult.success -and $unicodeResult.error.code -eq 'COMMAND_ABSENT' -and $unicodeResult.error.message.EndsWith($unicodeCommand)) ('Native Unicode arguments/output damaged '+$caseName)
        Assert-Variant ((Test-Path -LiteralPath (Join-Path $caseNative 'enterpriseai-sqlite.dll')) -eq ($selected -contains 'database')) ('Removed SQLite dependency reappeared '+$caseName)
        # Relocate only application files into a new owned path longer than MAX_PATH. Include
        # Cyrillic, Kazakh letters, spaces and supplementary Unicode; never link docs/db/model.
        $transferBase=Join-Path $caseRoot 'portable-transfer'
        $transferRoot=Join-Path $transferBase 'Перенос қазақ ә ғ қ ң ө ұ ү һ і 😀 с пробелами'
        while((Join-Path $transferRoot 'EnterpriseAI.exe').Length -le 335){$transferRoot=Join-Path $transferRoot 'Длинная папка құжаттар перенос приложения 😀'}
        New-Item -ItemType Directory -Force -Path $transferRoot,(Join-Path $transferRoot 'native') | Out-Null
        Copy-Item -LiteralPath $exe -Destination (Join-Path $transferRoot 'EnterpriseAI.exe')
        foreach($crt in $variantCrtNames){Copy-Item -LiteralPath (Join-Path $imageRoot $crt) -Destination $transferRoot}
        foreach($dll in $nativeNames){Copy-Item -LiteralPath (Join-Path $caseNative $dll) -Destination (Join-Path $transferRoot ('native/'+$dll))}
        $transferBefore=Get-VariantInventory $transferBase
        $transferExe=Join-Path $transferRoot 'EnterpriseAI.exe'
        $transferStatus=Invoke-Variant $transferExe 'status'
        Assert-Variant ((Get-NormalVariantPath $transferStatus.root) -eq (Get-NormalVariantPath $transferRoot)) ('Transferred native root mismatch '+$caseName)
        Assert-Variant ((@($transferStatus.modules | Sort-Object) -join ',') -eq ($expectedIds -join ',')) ('Transferred module mismatch '+$caseName)
        Assert-Variant ((@($transferStatus.panels | Sort-Object) -join ',') -eq ($expectedPanels -join ',')) ('Transferred pane mismatch '+$caseName)
        Assert-Variant ((@($transferStatus.commands | Sort-Object) -join ',') -eq (@($expectedCommands | Sort-Object) -join ',')) ('Transferred commands mismatch '+$caseName)
        $transferAfter=Get-VariantInventory $transferBase
        Assert-Variant ($transferAfter -ceq $transferBefore) ('Runtime created or changed a file in the transferred fixture '+$caseName)
        $row=[ordered]@{mask=$mask;modules=$selected;panels=@($status.panels);commands=@($status.commands);nativeBuild=$true;status=$true;unicodeArguments=$true;uiSmoke=$false;shortPathImeUnrestricted=$false;noDataIndependentLaunch=$true;cwd=$env:SystemRoot;root=$status.root;exeBytes=(Get-Item -LiteralPath $exe).Length;exeSha256=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant();nativeDlls=$nativeNames;appLocalCrtDlls=$variantCrtNames;unicodeLongTransfer=$true;transferredExeUtf16Length=$transferExe.Length;transferredRoot=$transferStatus.root;transferredUiSmoke=$false;fixtureInventoryUnchanged=$true;nativeCompileAndCliSeconds=$caseTimer.Elapsed.TotalSeconds;seconds=$caseTimer.Elapsed.TotalSeconds}
        $variantReport.Add($row)
        $variantRuntimeTasks.Add([pscustomobject]@{name=$caseName;exe=$exe;transferExe=$transferExe;transferBase=$transferBase;transferBefore=$transferBefore;row=$row})
        Write-Output ('BUILT native '+$caseName+' and CLI checks in '+[Math]::Round($caseTimer.Elapsed.TotalSeconds,1)+' s; UI remains queued')
        $interim=[ordered]@{success=$false;inProgress=$true;variantsCompiled=$variantReport.Count;variantsPassed=$variantPassed;results=@($variantReport.ToArray())}
        $interim | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $variantBuild 'test-results/native-module-removal.json') -Encoding utf8
    }
    # Compile all compositions before entering the focus-sensitive UI phase. The gate
    # lets an independent observer or the user complete UAC/tool setup without races.
    foreach($task in $variantRuntimeTasks){
        $runtimeTimer=[Diagnostics.Stopwatch]::StartNew()
        $smoke=Invoke-Variant $task.exe 'ui-smoke' 25
        Assert-Variant ($smoke.uiSmoke -eq $true) ('Native UI smoke failed '+$task.name)
        Assert-Variant ([string]::IsNullOrWhiteSpace($smoke.inputNotice)) ('Short-path native UI unexpectedly restricts IME '+$task.name)
        $transferSmoke=Invoke-Variant $task.transferExe 'ui-smoke' 25
        Assert-Variant ($transferSmoke.uiSmoke -eq $true) ('Transferred native SWT startup failed '+$task.name)
        Assert-Variant (![string]::IsNullOrWhiteSpace($transferSmoke.inputNotice) -and $transferSmoke.inputNotice -like '*IME*') ('Long-path input limitation was not disclosed '+$task.name)
        Assert-Variant ((Get-VariantInventory $task.transferBase) -ceq $task.transferBefore) ('Runtime created or changed a transferred fixture file '+$task.name)
        $task.row.uiSmoke=$true;$task.row.shortPathImeUnrestricted=$true;$task.row.transferredUiSmoke=$true
        $task.row['observerLoadedRuntime']=$smoke.observerLoadedRuntime;$task.row['transferredLoadedRuntime']=$transferSmoke.observerLoadedRuntime
        $task.row['longPathImeNotice']=$transferSmoke.inputNotice;$task.row['runtimeSeconds']=$runtimeTimer.Elapsed.TotalSeconds
        $task.row.seconds=$task.row.nativeCompileAndCliSeconds+$runtimeTimer.Elapsed.TotalSeconds
        $variantPassed++
        Write-Output ('PASS native '+$task.name+' short and Unicode-long SWT UI, app-local CRT and unchanged inventory')
        $interim=[ordered]@{success=$false;inProgress=$true;variantsCompiled=$variantReport.Count;variantsPassed=$variantPassed;results=@($variantReport.ToArray())}
        $interim | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $variantBuild 'test-results/native-module-removal.json') -Encoding utf8
    }
} catch {$variantFailure=$_;throw}
finally {
    $report=[ordered]@{completedAtUtc=[DateTime]::UtcNow.ToString('o');success=($null -eq $variantFailure -and $variantPassed -eq $variantReport.Count);variantsCompiled=$variantReport.Count;variantsPassed=$variantPassed;seconds=$variantStarted.Elapsed.TotalSeconds;results=@($variantReport.ToArray());limitations=@('Fixture inventories compare names, sizes, last-write times and SHA-256 only inside the owned transferred application tree; this is not a Process Monitor audit.','Read-only data and registry/file operation audit is a separate test.','Native builds use JavaCompiler compositions from the complete 128-case matrix and module-scoped resource copies.','No original module directories were deleted.','Explicit long-path launch and loaded CRT inspection use a developer-only Python observer; the shipped application does not use Python.','Long EXE/data paths are tested from a short valid working directory. Windows CreateProcessW rejects a working directory exceeding MAX_PATH before application startup: https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setcurrentdirectory');error=$(if($variantFailure){$variantFailure.ToString()}else{$null})}
    New-Item -ItemType Directory -Force -Path (Join-Path $variantBuild 'test-results') | Out-Null
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $variantBuild 'test-results/native-module-removal.json') -Encoding utf8
}
