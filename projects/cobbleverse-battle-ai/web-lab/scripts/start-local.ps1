param(
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot ".local-server.pid"
$outputLog = Join-Path $projectRoot ".local-server.log"
$errorLog = Join-Path $projectRoot ".local-server-error.log"
$localUrl = "http://localhost:3000"

function Test-LocalPort {
    $connection = Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    return $null -ne $connection
}

function Open-LocalPage {
    $browserInfo = New-Object System.Diagnostics.ProcessStartInfo
    $browserInfo.FileName = $localUrl
    $browserInfo.UseShellExecute = $true
    [System.Diagnostics.Process]::Start($browserInfo) | Out-Null
}

if (Test-Path -LiteralPath $pidFile) {
    $savedProcessId = [int](Get-Content -LiteralPath $pidFile -Raw)
    $savedProcess = Get-Process -Id $savedProcessId -ErrorAction SilentlyContinue
    if ($savedProcess -and (Test-LocalPort)) {
        Write-Host "Cobbleverse Battle Lab이 이미 실행 중입니다."
        if (-not $NoBrowser) {
            Open-LocalPage
        }
        exit 0
    }

    Remove-Item -LiteralPath $pidFile -Force
}

if (Test-LocalPort) {
    Write-Error "포트 3000을 다른 프로그램이 사용하고 있습니다. 해당 프로그램을 먼저 종료해 주세요."
    exit 1
}

$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npm) {
    Write-Error "Node.js와 npm을 찾을 수 없습니다. Node.js 22.13 이상을 설치해 주세요."
    exit 1
}

if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "node_modules"))) {
    Write-Host "처음 실행을 위한 패키지를 설치합니다..."
    Push-Location $projectRoot
    try {
        & $npm.Source ci
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci가 종료 코드 $LASTEXITCODE로 실패했습니다."
        }
    }
    finally {
        Pop-Location
    }
}

Remove-Item -LiteralPath $outputLog -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $errorLog -Force -ErrorAction SilentlyContinue

$command = "`"$($npm.Source)`" run dev 1>`"$outputLog`" 2>`"$errorLog`""
$serverInfo = New-Object System.Diagnostics.ProcessStartInfo
$serverInfo.FileName = $env:ComSpec
$serverInfo.Arguments = "/d /s /c `"$command`""
$serverInfo.WorkingDirectory = $projectRoot
$serverInfo.UseShellExecute = $true
$serverInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
$serverProcess = [System.Diagnostics.Process]::Start($serverInfo)

Set-Content -LiteralPath $pidFile -Value $serverProcess.Id -Encoding ascii

$started = $false
for ($attempt = 0; $attempt -lt 80; $attempt += 1) {
    Start-Sleep -Milliseconds 250
    $serverProcess.Refresh()
    if ($serverProcess.HasExited) {
        break
    }
    if (Test-LocalPort) {
        $started = $true
        break
    }
}

if (-not $started) {
    if (-not $serverProcess.HasExited) {
        & taskkill.exe /PID $serverProcess.Id /T /F | Out-Null
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    $details = if (Test-Path -LiteralPath $errorLog) {
        Get-Content -LiteralPath $errorLog -Raw
    }
    else {
        "오류 로그가 없습니다."
    }
    Write-Error "로컬 서버가 제한 시간 안에 시작되지 않았습니다.`n$details"
    exit 1
}

Write-Host "Cobbleverse Battle Lab을 시작했습니다: $localUrl"
if (-not $NoBrowser) {
    Open-LocalPage
}
