param(
    [int[]]$Masks=@(0,118,126,125,123,119,111,95,63),
    [switch]$IncludeFull,
    [string]$PythonExecutable=$env:ENTERPRISE_TEST_PYTHON,
    [string]$GuiGate,
    [string]$ExecutableName='миниИИ.exe',
    [string]$EvidencePrefix='pink'
)
$ErrorActionPreference='Stop'
# The previous DLL profile is superseded. This compatibility entry point exercises
# the supported static one-EXE profile and keeps prior reports and variants intact.
if($IncludeFull){$Masks=@($Masks)+@(127)}
$forward=@{Masks=$Masks;ExecutableName=$ExecutableName;EvidencePrefix=$EvidencePrefix}
if($PythonExecutable){$forward.PythonExecutable=$PythonExecutable}
if($GuiGate){$forward.GuiGate=$GuiGate}
& (Join-Path $PSScriptRoot 'static-native-variants.ps1') @forward
if($LASTEXITCODE -ne 0){throw 'Static native module-removal checks failed'}