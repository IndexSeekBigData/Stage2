<#
.SYNOPSIS
Stops only Java processes owned by the deployment registry. Leaves books, databases and logs intact.
#>
[CmdletBinding()]
param([string]$DataDir = (Join-Path $PSScriptRoot "..\data"))
. (Join-Path $PSScriptRoot "deployment-common.ps1")
$DataDir = Get-DeploymentDataDir $DataDir
$guard = Enter-DeploymentLock $DataDir
try {
    $config = Read-DeploymentConfig $DataDir
    if ($null -eq $config) { Write-Host "No managed deployment in $DataDir"; return }
    if ($config.status -eq "stopped") { Write-Host "Deployment is already stopped."; return }
    $failed = $false
    $records = @($config.processes)
    # Reverse startup order: Control stops first, before its service dependencies.
    [array]::Reverse($records)
    foreach ($record in $records) {
        if ($record.status -ne "running") { continue }
        try {
            $managed = Get-ManagedProcess $record $config
            if ($null -ne $managed) {
                Stop-Process -Id $managed.Id -ErrorAction Stop
                if (-not $managed.WaitForExit(10000)) { throw "PID $($managed.Id) did not exit." }
                Write-Host "Stopped $($record.service)[$($record.instance)] PID=$($record.pid)"
            }
            $record.status = "stopped"
        } catch {
            $failed = $true
            Write-Warning $_.Exception.Message
        }
    }
    $config.status = "stopped"
    if ($failed) { $config.status = "stop_failed" }
    $config.stopped_at = [datetime]::UtcNow.ToString("o")
    Save-DeploymentConfig $config
    if ($failed) { throw "Some processes could not be safely identified/stopped. Their registry entries were retained." }
} finally { $guard.Dispose() }
