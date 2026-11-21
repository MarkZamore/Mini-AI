param([string]$JavaHome='', [int[]]$Scales=@(100,125,150,200))
$ErrorActionPreference='Stop'
$taskSource=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskProject=Split-Path -Parent $taskSource
if(!$JavaHome){$JavaHome=Join-Path $taskSource 'build/tools/graalvm-community-openjdk-25.0.2+10.1'}
$taskJava=Join-Path $JavaHome 'bin/java.exe'
$taskJavac=Join-Path $JavaHome 'bin/javac.exe'
$taskRuntime=Join-Path $taskSource 'build/restyle-ui-root'
New-Item -ItemType Directory -Force -Path $taskRuntime | Out-Null
foreach($taskName in @('docs','db','model','native')){
    $taskTarget=if($taskName -eq 'native'){Join-Path $taskSource 'build/native'}else{Join-Path $taskProject $taskName}
    $taskLink=Join-Path $taskRuntime $taskName
    if(!(Test-Path -LiteralPath $taskLink)){New-Item -ItemType Junction -Path $taskLink -Target $taskTarget | Out-Null}
    $taskItem=Get-Item -LiteralPath $taskLink
    if($taskItem.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath(@($taskItem.Target)[0]) -ne [IO.Path]::GetFullPath($taskTarget)){throw "Unexpected existing fixture path: $taskLink"}
}
$taskMain=Join-Path $taskSource 'build/classes/java/main'
$taskResources=Join-Path $taskSource 'build/resources/main'
$taskLibraries=(Get-ChildItem -LiteralPath (Join-Path $taskSource 'build/runtime-libs') -Filter '*.jar').FullName
$taskClasspath=@($taskMain,$taskResources)+$taskLibraries -join ';'
$taskClasses=Join-Path $taskSource 'build/restyle-ui-classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
& $taskJavac -encoding UTF-8 -classpath $taskClasspath -d $taskClasses (Join-Path $PSScriptRoot 'UiDpiTest.java') (Join-Path $PSScriptRoot 'RestyleUiTest.java')
if($LASTEXITCODE -ne 0){throw 'Restyle fixture compilation failed'}
$taskRunClasspath=@($taskClasses,$taskClasspath) -join ';'
foreach($taskScale in $Scales){
    if($taskScale -notin @(100,125,150,200)){throw "Unsupported test scale: $taskScale"}
    & $taskJava -XX:-UsePerfData -XX:+DisableAttachMechanism -XX:ErrorFile=NUL --enable-native-access=ALL-UNNAMED ('-Dswt.autoScale='+$taskScale) '-Dswt.autoScale.updateOnRuntime=false' -classpath $taskRunClasspath ai.enterprise.shell.RestyleUiTest $taskSource $taskScale restyle-ui
    if($LASTEXITCODE -ne 0){throw "Full-catalog restyle UI failed at $taskScale percent"}
}
