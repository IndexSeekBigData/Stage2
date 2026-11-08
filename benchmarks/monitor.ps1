<#
.SYNOPSIS
Samples CPU and memory once per second for every process of the deployment and for the whole host.

.DESCRIPTION
Started by run-search.ps1 and run-pipeline-bench.ps1 next to each run; it stops when the StopFile
appears. Output CSV (long format): timestamp, elapsed_s, scope (process|host), name, pid, cpu_pct, memory_mb.
Process CPU % = increase of TotalProcessorTime / (interval x logical processors), so 100 % is the
whole machine, like the host value. Process memory is the working set. Host CPU and memory come from
WMI classes, whose names do not depend on the Windows language (Get-Counter paths do).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DataDir,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$StopFile = "",
    [int]$IntervalMs = 1000,
    [int]$MaxSeconds = 7200
)
$ErrorActionPreference = "Stop"
$invariant = [Globalization.CultureInfo]::InvariantCulture
$cores = [Environment]::ProcessorCount

# Names and PIDs from the deployment registry written by start-all.ps1 (the same PIDs as pids.txt).
$config = Get-Content -LiteralPath (Join-Path $DataDir "run-config.json") -Raw | ConvertFrom-Json
$targets = @()
foreach ($record in @($config.processes | Where-Object { $_.status -eq "running" })) {
    $targets += [pscustomobject]@{ Name = ("{0}-{1}" -f $record.service, $record.instance); Id = [int]$record.pid; Last = $null }
}

Set-Content -LiteralPath $Out -Value "timestamp,elapsed_s,scope,name,pid,cpu_pct,memory_mb" -Encoding ASCII
$clock = [Diagnostics.Stopwatch]::StartNew()
$previous = 0.0
foreach ($target in $targets) {
    $process = Get-Process -Id $target.Id -ErrorAction SilentlyContinue
    if ($null -ne $process) { $target.Last = $process.TotalProcessorTime.TotalSeconds }
}

while ($true) {
    Start-Sleep -Milliseconds $IntervalMs
    $now = $clock.Elapsed.TotalSeconds
    $interval = $now - $previous
    $previous = $now
    $stamp = [DateTime]::UtcNow.ToString("o")
    $elapsed = $now.ToString("0.000", $invariant)
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($target in $targets) {
        $process = Get-Process -Id $target.Id -ErrorAction SilentlyContinue
        if ($null -eq $process) { continue }
        $cpu = $process.TotalProcessorTime.TotalSeconds
        if ($null -ne $target.Last -and $interval -gt 0) {
            $percent = 100.0 * ($cpu - $target.Last) / ($interval * $cores)
            $memory = $process.WorkingSet64 / 1MB
            $lines.Add(("{0},{1},process,{2},{3},{4},{5}" -f $stamp, $elapsed, $target.Name, $target.Id,
                $percent.ToString("0.00", $invariant), $memory.ToString("0.0", $invariant)))
        }
        $target.Last = $cpu
    }
    $processor = Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
    $os = Get-CimInstance Win32_OperatingSystem
    $usedMb = ($os.TotalVisibleMemorySize - $os.FreePhysicalMemory) / 1KB
    $lines.Add(("{0},{1},host,host,,{2},{3}" -f $stamp, $elapsed,
        ([double]$processor.PercentProcessorTime).ToString("0.00", $invariant), $usedMb.ToString("0.0", $invariant)))
    Add-Content -LiteralPath $Out -Value $lines -Encoding ASCII
    if ($StopFile -and (Test-Path -LiteralPath $StopFile)) { break }
    if ($now -gt $MaxSeconds) { break }
}
