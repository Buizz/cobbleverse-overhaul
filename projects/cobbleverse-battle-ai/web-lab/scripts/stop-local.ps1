$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot ".local-server.pid"

if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host "기록된 로컬 서버가 없습니다. 이미 종료된 상태입니다."
    exit 0
}

$serverProcessId = [int](Get-Content -LiteralPath $pidFile -Raw)
$serverProcess = Get-Process -Id $serverProcessId -ErrorAction SilentlyContinue

if ($serverProcess) {
    & taskkill.exe /PID $serverProcessId /T /F | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "프로세스 $serverProcessId 종료에 실패했습니다."
        exit 1
    }
}

Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
Write-Host "Cobbleverse Battle Lab 로컬 서버를 종료했습니다."
