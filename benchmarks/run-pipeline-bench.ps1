<#
.SYNOPSIS
Pipeline experiments D (indexing vs Control workers), E (ingestion vs Control workers and Ingestion
replicas) and the data for F (coordination cost per phase).

.DESCRIPTION
D  Restores the corpus snapshot (all books INDEXED), starts the deployment with -Workers Control
   workers and re-queues every book with POST /control/books/{id}/retry, which moves INDEXED books
   back to DOWNLOADED: only indexing is measured, nothing is downloaded.
E  Starts from an empty data folder and registers the books of ingest-ids.txt, so each run
   downloads them from Project Gutenberg. The result depends on the network: say so in the report.
For each run, one row goes to <OutDir>\pipeline.csv and the per-book timestamps of Control
(GET /control/books) and GET /control/metrics are saved in <OutDir>\raw\. plot.py computes the
phase breakdown (queue wait, download, wait between phases, indexing) from those timestamps.

.EXAMPLE
.\benchmarks\run-pipeline-bench.ps1 -Snapshot C:\BD\bench\snapshot -Experiments D
.\benchmarks\run-pipeline-bench.ps1 -Experiments E -IngestionReplicas 1,2
#>
[CmdletBinding()]
param(
    [string]$Snapshot = "",
    [string]$WorkRoot = "C:\BD\bench\work",
    [string]$OutDir = "",
    [string[]]$Experiments = @("D", "E"),
    [string[]]$Workers = @("1", "2", "4", "8"),
    [string[]]$IngestionReplicas = @("1"),
    [ValidateRange(1, 20)][int]$Repetitions = 3,
    [string]$IngestIds = (Join-Path $PSScriptRoot "ingest-ids.txt"),
    [int]$TimeoutSeconds = 1800,
    [string]$GutenbergBaseUrl = "https://www.gutenberg.org",
    [string]$JavaExe = ""
)
. (Join-Path $PSScriptRoot "bench-common.ps1")
Use-Java $JavaExe
$OutDir = New-ResultsDir $OutDir
Save-HostInfo $OutDir
$csv = Join-Path $OutDir "pipeline.csv"
$failures = Join-Path $OutDir "failures.csv"
$header = "started_at,finished_at,experiment,label,control_workers,ingestion_replicas,repetition,books,indexed,failed,not_found,elapsed_s,books_per_s"

$failedRuns = 0
$runs = @()
foreach ($experiment in (Split-List $Experiments)) {
    switch ($experiment.ToUpperInvariant()) {
        "D" {
            if (-not $Snapshot) { throw "Experiment D needs -Snapshot." }
            foreach ($w in (Split-List $Workers)) { $runs += [pscustomobject]@{ Experiment = "D"; Workers = [int]$w; Ingestion = 1 } }
        }
        "E" {
            foreach ($i in (Split-List $IngestionReplicas)) {
                foreach ($w in (Split-List $Workers)) { $runs += [pscustomobject]@{ Experiment = "E"; Workers = [int]$w; Ingestion = [int]$i } }
            }
        }
        default { throw "Unknown experiment '$experiment'. Use D or E." }
    }
}
$ingestList = @(Read-IdList $IngestIds)

foreach ($run in $runs) {
    for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
        $name = "{0}-workers{1}-ingestion{2}-rep{3}" -f $run.Experiment, $run.Workers, $run.Ingestion, $repetition
        Write-Host ""
        Write-Host ("=== {0} ({1:HH:mm:ss})" -f $name, (Get-Date))
        $dataDir = Join-Path $WorkRoot $name
        if ($run.Experiment -eq "D") {
            Restore-Snapshot $Snapshot $dataDir
        } else {
            # A fresh, empty data folder: every book is new and must be downloaded.
            if (Test-Path -LiteralPath $dataDir) { throw "Run directory already exists: $dataDir" }
            New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
        }
        $monitor = $null
        try {
            $parameters = @{ DataDir = $dataDir; ControlWorkers = $run.Workers; IngestionReplicas = $run.Ingestion; SearchReplicas = 1; GutenbergBaseUrl = $GutenbergBaseUrl }
            if ($JavaExe) { $parameters.JavaExe = $JavaExe }
            $config = Start-Deployment $parameters
            $control = $config.endpoints.control
            $monitor = Start-Monitor $dataDir (Join-Path $OutDir "raw\$name.monitor.csv")
            if ($run.Experiment -eq "D") {
                $ids = @(Get-ControlBooks $control "INDEXED" | ForEach-Object { [int]$_.book_id })
                if ($ids.Count -eq 0) { throw "The snapshot has no INDEXED books." }
                $started = [DateTime]::UtcNow
                foreach ($id in $ids) { Invoke-RestMethod -Method Post -Uri "$control/control/books/$id/retry" | Out-Null }
            } else {
                $ids = $ingestList
                $started = [DateTime]::UtcNow
                $body = @{ ids = $ids } | ConvertTo-Json -Compress
                Invoke-RestMethod -Method Post -Uri "$control/control/books" -Body $body -ContentType "application/json" | Out-Null
            }
            $status = Wait-ControlIdle $control $TimeoutSeconds
            $finished = [DateTime]::UtcNow
            $books = @(Get-ControlBooks $control)
            ConvertTo-Json -InputObject @($books) -Depth 5 | Set-Content -LiteralPath (Join-Path $OutDir "raw\$name.books.json") -Encoding UTF8
            Invoke-RestMethod -Uri "$control/control/metrics" | ConvertTo-Json -Depth 5 |
                Set-Content -LiteralPath (Join-Path $OutDir "raw\$name.metrics.json") -Encoding UTF8
            $runBooks = @($books | Where-Object { $ids -contains [int]$_.book_id })
            $indexed = @($runBooks | Where-Object { $_.state -eq "INDEXED" }).Count
            $failed = @($runBooks | Where-Object { $_.state -eq "FAILED" }).Count
            $notFound = @($runBooks | Where-Object { $_.state -eq "NOT_FOUND" }).Count
            $elapsed = ($finished - $started).TotalSeconds
            Add-CsvLine $csv $header @($started.ToString("o"), $finished.ToString("o"), $run.Experiment, $name, $run.Workers,
                $run.Ingestion, $repetition, $ids.Count, $indexed, $failed, $notFound,
                (Format-Number $elapsed), (Format-Number ($indexed / $elapsed)))
            if ($failed -gt 0) { throw "$failed books FAILED in $name; run is not valid" }
            Write-Host ("    books={0} indexed={1} failed={2} not_found={3} elapsed={4}s books/s={5}" -f $ids.Count, $indexed,
                $failed, $notFound, (Format-Number $elapsed "0.0"), (Format-Number ($indexed / $elapsed) "0.00"))
        } catch {
            $failedRuns++
            Write-Warning "$name failed: $($_.Exception.Message)"
            Add-CsvLine $failures "timestamp,run,error" @((Get-Date).ToString("o"), $name, $_.Exception.Message)
        } finally {
            Stop-Monitor $monitor
            Stop-Deployment $dataDir
            Save-RunConfig $dataDir (Join-Path $OutDir "raw\$name.run-config.json")
        }
    }
}
Write-Host ""
Write-Host "Results: $csv. Next: python benchmarks\plots\plot.py $OutDir"

if ($failedRuns -gt 0) { throw "$failedRuns pipeline runs failed; inspect failures.csv" }
