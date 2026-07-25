@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "START_OPTIONS="
if /i "%COBBLEVERSE_NO_BROWSER%"=="1" set "START_OPTIONS=-NoBrowser"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" %START_OPTIONS%
if errorlevel 1 (
  echo.
  echo Failed to start Cobbleverse Battle Lab.
  pause
  exit /b 1
)
endlocal
exit /b 0
