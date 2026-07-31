@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\create-portable-zip.ps1"
if errorlevel 1 (
  echo.
  echo Failed to create the portable ZIP.
  pause
  exit /b 1
)
endlocal
exit /b 0
