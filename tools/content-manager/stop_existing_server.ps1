param(
    [Parameter(Mandatory = $true)]
    [string]$ManagerPath,
    [Parameter(Mandatory = $true)]
    [string]$RepositoryRoot
)

$resolvedManager = [System.IO.Path]::GetFullPath($ManagerPath)
$resolvedRoot = [System.IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\')
$managerNeedle = $resolvedManager.ToLowerInvariant()
$rootNeedle = $resolvedRoot.ToLowerInvariant()
$managerName = [System.IO.Path]::GetFileName($resolvedManager).ToLowerInvariant()
$listenerPids = @(
    Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess
)

$servers = Get-CimInstance Win32_Process | Where-Object {
    $command = if ($_.CommandLine) { $_.CommandLine.ToLowerInvariant() } else { "" }
    $name = $_.Name.ToLowerInvariant()
    $isPython = $name -in @("python.exe", "python3.exe", "py.exe")
    $sameRepository = $command.Contains($managerNeedle) -and $command.Contains($rootNeedle)
    $defaultPortServer = $_.ProcessId -in $listenerPids -and $command.Contains($managerName)
    $isPython -and
        ($sameRepository -or $defaultPortServer) -and
        $command -match '(?:^|\s)api(?:\s|$)'
}

if (-not $servers) {
    Write-Host "[INFO] No previous content manager server was found."
    exit 0
}

$servers | Sort-Object ProcessId -Descending | ForEach-Object {
    Write-Host "[INFO] Stopping previous content manager server (PID $($_.ProcessId))."
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
}

Start-Sleep -Milliseconds 300
exit 0
