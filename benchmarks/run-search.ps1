<#
.SYNOPSIS
Search load experiments A (clients), B (Search replicas) and C (HTTP threads).

.DESCRIPTION
For every configuration and repetition: restores the corpus snapshot, starts the deployment with
start-all.ps1, starts monitor.ps1, runs the load client against every Search replica, then stops
everything. Only one factor changes per experiment:
  A  clients = -Clients            1 Search replica, default HTTP threads
  B  replicas = -Replicas           clients = -SaturationClients (the saturation point found in A)
  C  HTTP threads = -ServerThreads  1 Search replica, clients = -ThreadClients
One row per run is appended to <OutDir>\search.csv (label = run name); CPU/memory samples and the
deployment configuration of each run go to <OutDir>\raw\.

.EXAMPLE
.\benchmarks\run-search.ps1 -Snapshot C:\BD\bench\snapshot -Experiments A
.\benchmarks\run-search.ps1 -Snapshot C:\BD\bench\snapshot -Experiments B,C -SaturationClients 64
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Snapshot,
    [string]$DataDir = "C:\BD\bench\work\search",
    [string]$OutDir = "",
    [string[]]$Experiments = @("A", "B", "C"),
    [string[]]$Clients = @("1", "4", "16", "64", "128"),
    [string[]]$Replicas = @("1", "2", "3"),
    [string[]]$ServerThreads = @("16", "32", "250"),
    [int]$SaturationClients = 64,
    [int]$ThreadClients = 128,
    [ValidateRange(1, 20)][int]$Repetitions = 3,
    [int]$WarmupSeconds = 5,
    [int]$DurationSeconds = 20,
    [string]$Queries = (Join-Path $PSScriptRoot "queries.txt"),
    [string]$JavaExe = ""
)
. (Join-Path $PSScriptRoot "bench-common.ps1")
Use-Java $JavaExe
$OutDir = New-ResultsDir $OutDir
Save-HostInfo $OutDir
$client = Join-Path $PSScriptRoot "load-client\target\load-client-1.0.0.jar"
if (-not (Test-Path -LiteralPath $client)) { throw "Build the load client first: mvn -f benchmarks\load-client\pom.xml package" }
$Queries = (Resolve-Path -LiteralPath $Queries).Path
$csv = Join-Path $OutDir "search.csv"
$failures = Join-Path $OutDir "failures.csv"

$failedRuns = 0
$runs = @()
foreach ($experiment in (Split-List $Experiments)) {
    switch ($experiment.ToUpperInvariant()) {
        "A" { foreach ($c in (Split-List $Clients)) { $runs += [pscustomobject]@{ Experiment = "A"; Clients = [int]$c; Replicas = 1; Threads = 0 } } }
        "B" { foreach ($r in (Split-List $Replicas)) { $runs += [pscustomobject]@{ Experiment = "B"; Clients = $SaturationClients; Replicas = [int]$r; Threads = 0 } } }
        "C" { foreach ($t in (Split-List $ServerThreads)) { $runs += [pscustomobject]@{ Experiment = "C"; Clients = $ThreadClients; Replicas = 1; Threads = [int]$t } } }
        default { throw "Unknown experiment '$experiment'. Use A, B or C." }
    }
}
$minutes = $runs.Count * $Repetitions * ($WarmupSeconds + $DurationSeconds + 25) / 60
Write-Host ("{0} runs x {1} repetitions, about {2:0} minutes. Do not use the PC meanwhile." -f $runs.Count, $Repetitions, $minutes)

foreach ($run in $runs) {
    for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
        $name = "{0}-clients{1}-replicas{2}-threads{3}-rep{4}" -f $run.Experiment, $run.Clients, $run.Replicas, $run.Threads, $repetition
        Write-Host ""
        Write-Host ("=== {0} ({1:HH:mm:ss})" -f $name, (Get-Date))
        $runData = Join-Path $DataDir $name
        Restore-Snapshot $Snapshot $runData
        $monitor = $null
        try {
            $parameters = @{ DataDir = $runData; SearchReplicas = $run.Replicas; ServerThreads = $run.Threads; IngestionReplicas = 1; ControlWorkers = 1 }
            if ($JavaExe) { $parameters.JavaExe = $JavaExe }
            $config = Start-Deployment $parameters
            $monitor = Start-Monitor $runData (Join-Path $OutDir "raw\$name.monitor.csv")
            $targets = @($config.endpoints.search) -join ","
            $load = Invoke-Native {
                java -Xms512m -Xmx2G -jar $client --targets $targets --concurrency $run.Clients `
                    --warmup "$($WarmupSeconds)s" --duration "$($DurationSeconds)s" --queries $Queries --out $csv `
                    --experiment $run.Experiment --label $name --repetition $repetition `
                    --search-replicas $run.Replicas --server-threads $run.Threads
            }
            $load.Output | Where-Object { $_ -notmatch "JAVA_TOOL_OPTIONS" } | ForEach-Object { Write-Host "    $_" }
            if ($load.ExitCode -ne 0) { throw "Load client exit code $($load.ExitCode)" }
        } catch {
            $failedRuns++
            Write-Warning "$name failed: $($_.Exception.Message)"
            Add-CsvLine $failures "timestamp,run,error" @((Get-Date).ToString("o"), $name, $_.Exception.Message)
        } finally {
            Stop-Monitor $monitor
            Stop-Deployment $runData
            Save-RunConfig $runData (Join-Path $OutDir "raw\$name.run-config.json")
        }
    }
}
Write-Host ""
Write-Host "Results: $csv. Next: python benchmarks\plots\plot.py $OutDir"

if ($failedRuns -gt 0) { throw "$failedRuns search runs failed; inspect failures.csv" }
