param(
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot ".local-server.pid"
$outputLog = Join-Path $projectRoot ".local-server.log"
$errorLog = Join-Path $projectRoot ".local-server-error.log"
$localUrl = "http://localhost:3000"

function Test-LocalPort {
    try {
        $response = Invoke-WebRequest -Uri $localUrl -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
        return $response.Content -like "*Cobbleverse Battle Lab*"
    }
    catch {
        return $false
    }
}

function Open-LocalPage {
    $browserInfo = New-Object System.Diagnostics.ProcessStartInfo
    $browserInfo.FileName = $localUrl
    $browserInfo.UseShellExecute = $true
    [System.Diagnostics.Process]::Start($browserInfo) | Out-Null
}

function Read-LogTail {
    param(
        [string]$Path,
        [int]$LineCount = 80
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return ""
    }

    $content = Get-Content -LiteralPath $Path -Tail $LineCount -ErrorAction SilentlyContinue
    if (-not $content) {
        return ""
    }

    return ($content -join [Environment]::NewLine)
}

if (Test-Path -LiteralPath $pidFile) {
    $savedProcessId = [int](Get-Content -LiteralPath $pidFile -Raw)
    $savedProcess = Get-Process -Id $savedProcessId -ErrorAction SilentlyContinue
    if ($savedProcess -and (Test-LocalPort)) {
        Write-Host "Cobbleverse Battle Lab is already running: $localUrl"
        if (-not $NoBrowser) {
            Open-LocalPage
        }
        exit 0
    }

    Remove-Item -LiteralPath $pidFile -Force
}

if (Test-LocalPort) {
    Write-Host "Cobbleverse Battle Lab is already running: $localUrl"
    if (-not $NoBrowser) {
        Open-LocalPage
    }
    exit 0
}

$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npm) {
    Write-Error "Could not find npm. Install Node.js 22.13 or newer."
    exit 1
}

if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "node_modules"))) {
    Write-Host "Installing packages for the first run..."
    Push-Location $projectRoot
    try {
        & $npm.Source ci
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci failed with exit code $LASTEXITCODE."
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
$startupTimeoutSeconds = 90
$pollIntervalMilliseconds = 500
$maxAttempts = [Math]::Ceiling(($startupTimeoutSeconds * 1000) / $pollIntervalMilliseconds)
for ($attempt = 0; $attempt -lt $maxAttempts; $attempt += 1) {
    Start-Sleep -Milliseconds $pollIntervalMilliseconds
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
        try {
            & taskkill.exe /PID $serverProcess.Id /T /F 2>$null | Out-Null
        }
        catch {
        }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    $errorDetails = Read-LogTail -Path $errorLog
    $outputDetails = Read-LogTail -Path $outputLog
    $details = @(
        if ($errorDetails) {
            "[stderr]"
            $errorDetails
        }
        if ($outputDetails) {
            "[stdout]"
            $outputDetails
        }
    ) -join [Environment]::NewLine
    if (-not $details) {
        $details = "No log output was captured."
    }
    Write-Error "Local server did not start within $startupTimeoutSeconds seconds.`n$details"
    exit 1
}

Write-Host "Cobbleverse Battle Lab started: $localUrl"
if (-not $NoBrowser) {
    Open-LocalPage
}
