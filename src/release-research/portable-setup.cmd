@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Portable-Setup.ps1" %*
if errorlevel 1 (
  echo.
  echo Setup stopped. Read the message above; run again to resume.
  pause
  exit /b 1
)
endlocal
