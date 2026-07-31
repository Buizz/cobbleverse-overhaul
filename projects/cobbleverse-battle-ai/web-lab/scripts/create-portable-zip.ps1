$ErrorActionPreference = "Stop"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$webLabRoot = Split-Path -Parent $PSScriptRoot
$battleAiRoot = Split-Path -Parent $webLabRoot
$repositoryRoot = (Resolve-Path (Join-Path $battleAiRoot "..\..")).Path
$releaseDirectory = Join-Path $webLabRoot "releases"
$archivePath = Join-Path $releaseDirectory "cobbleverse-battle-lab-portable.zip"
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("cobbleverse-battle-lab-package-" + [guid]::NewGuid().ToString("N"))
$packageRoot = Join-Path $stageRoot "cobbleverse-battle-lab"
$portableBattleAiRoot = Join-Path $packageRoot "projects\cobbleverse-battle-ai"
$portableWebLabRoot = Join-Path $portableBattleAiRoot "web-lab"

function Copy-RequiredItem {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    if (-not (Test-Path -LiteralPath $Source)) {
        throw "Required package input is missing: $Source"
    }

    $parent = Split-Path -Parent $Destination
    if ($parent) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

try {
    New-Item -ItemType Directory -Path $portableWebLabRoot -Force | Out-Null

    foreach ($directory in @(".openai", "app", "lib", "public", "worker")) {
        Copy-RequiredItem `
            -Source (Join-Path $webLabRoot $directory) `
            -Destination (Join-Path $portableWebLabRoot $directory)
    }

    New-Item -ItemType Directory -Path (Join-Path $portableWebLabRoot "scripts") -Force | Out-Null
    foreach ($script in @(
        "battle-sweep-worker.mjs",
        "start-local.ps1",
        "stop-local.ps1",
        "sync-cobblemon-localization.mjs"
    )) {
        Copy-RequiredItem `
            -Source (Join-Path $webLabRoot "scripts\$script") `
            -Destination (Join-Path $portableWebLabRoot "scripts\$script")
    }

    foreach ($file in @(
        ".gitignore",
        "eslint.config.mjs",
        "local-audio-catalog.ts",
        "local-battle-sweep-vite-plugin.ts",
        "local-workspace-vite-plugin.ts",
        "next.config.ts",
        "package-lock.json",
        "postcss.config.mjs",
        "sites-vite-plugin.ts",
        "start.bat",
        "stop.bat",
        "THIRD_PARTY_NOTICES.md",
        "tsconfig.json",
        "vite.config.ts"
    )) {
        Copy-RequiredItem `
            -Source (Join-Path $webLabRoot $file) `
            -Destination (Join-Path $portableWebLabRoot $file)
    }

    Copy-RequiredItem `
        -Source (Join-Path $battleAiRoot "data\ai") `
        -Destination (Join-Path $portableBattleAiRoot "data\ai")

    $sourcePackage = Get-Content -LiteralPath (Join-Path $webLabRoot "package.json") -Raw | ConvertFrom-Json
    $portablePackage = [ordered]@{
        name = $sourcePackage.name
        version = $sourcePackage.version
        private = $true
        engines = $sourcePackage.engines
        scripts = [ordered]@{
            dev = $sourcePackage.scripts.dev
        }
        dependencies = $sourcePackage.dependencies
        devDependencies = $sourcePackage.devDependencies
        type = $sourcePackage.type
    }
    $portablePackageJson = $portablePackage | ConvertTo-Json -Depth 20
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        (Join-Path $portableWebLabRoot "package.json"),
        $portablePackageJson + [Environment]::NewLine,
        $utf8WithoutBom
    )

    @'
@echo off
setlocal
cd /d "%~dp0projects\cobbleverse-battle-ai\web-lab"
call start.bat
exit /b %errorlevel%
'@ | Set-Content -LiteralPath (Join-Path $packageRoot "START-WEB-LAB.bat") -Encoding ascii

    @'
Cobbleverse Battle Lab portable package

Requirements:
- Windows 10 or newer
- Node.js 22.13 or newer
- Internet access during the first launch (npm packages are downloaded once)

Run:
1. Extract this ZIP to a writable folder.
2. Double-click START-WEB-LAB.bat.
3. The first launch runs npm ci, so it can take several minutes.
4. Open http://localhost:3000 if the browser does not open automatically.

Stop:
- Run projects\cobbleverse-battle-ai\web-lab\stop.bat.

This package intentionally excludes node_modules, build outputs, caches, tests,
logs, local settings, and repository-only trainer source data. Generated runtime
data under public\data is included.
'@ | Set-Content -LiteralPath (Join-Path $packageRoot "README.txt") -Encoding utf8

    New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
    if (Test-Path -LiteralPath $archivePath) {
        Remove-Item -LiteralPath $archivePath -Force
    }
    Compress-Archive -LiteralPath $packageRoot -DestinationPath $archivePath -CompressionLevel Optimal

    $archive = Get-Item -LiteralPath $archivePath
    Write-Host "Portable ZIP created: $($archive.FullName)"
    Write-Host ("Archive size: {0:N2} MB" -f ($archive.Length / 1MB))
}
finally {
    if (Test-Path -LiteralPath $stageRoot) {
        Remove-Item -LiteralPath $stageRoot -Recurse -Force
    }
}
