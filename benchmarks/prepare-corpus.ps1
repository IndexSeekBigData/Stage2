<#
.SYNOPSIS
Downloads and indexes the benchmark corpus once with the normal pipeline and saves it as a snapshot.

.DESCRIPTION
Starts the deployment on an empty -DataDir, registers the ids of corpus-ids.txt through Control
(Control -> Ingestion -> Indexing), waits until no book is active, stops everything and mirrors the
data folder to -Snapshot. Every later experiment restores this snapshot, so the network and
Gutenberg do not affect indexing and search measurements.

.EXAMPLE
.\benchmarks\prepare-corpus.ps1
#>
[CmdletBinding()]
param(
    [string]$DataDir = "C:\BD\bench\corpus",
    [string]$Snapshot = "C:\BD\bench\snapshot",
    [string]$CorpusIds = (Join-Path $PSScriptRoot "corpus-ids.txt"),
    [int]$ControlWorkers = 4,
    [int]$TimeoutSeconds = 3600,
    [string]$JavaExe = ""
)
. (Join-Path $PSScriptRoot "bench-common.ps1")
Use-Java $JavaExe
if ((Test-Path -LiteralPath $DataDir) -and @(Get-ChildItem -LiteralPath $DataDir -Force).Count -gt 0) {
    throw "$DataDir is not empty. Use a new folder (or delete it) so the corpus is built from scratch."
}
$ids = @(Read-IdList $CorpusIds)
$parameters = @{ DataDir = $DataDir; ControlWorkers = $ControlWorkers }
if ($JavaExe) { $parameters.JavaExe = $JavaExe }
$config = Start-Deployment $parameters
$missing = @()
try {
    $control = $config.endpoints.control
    $body = @{ ids = $ids } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$control/control/books" -Body $body -ContentType "application/json" | Out-Null
    Write-Host "Registered $($ids.Count) books. Downloading and indexing..."
    $status = Wait-ControlIdle $control $TimeoutSeconds
    Write-Host ("States: " + ($status.states | ConvertTo-Json -Compress))
    $missing = @(Get-ControlBooks $control | Where-Object { $_.state -ne "INDEXED" })
    foreach ($book in $missing) { Write-Warning "Book $($book.book_id): $($book.state) $($book.last_error)" }
} finally {
    Stop-Deployment $DataDir
}
New-Item -ItemType Directory -Path $Snapshot -Force | Out-Null
$copy = Invoke-Native { robocopy $DataDir $Snapshot /MIR /R:2 /W:1 /NFL /NDL /NJH /NJS /NP /XD logs /XF run-config.json pids.txt deployment.lock }
if ($copy.ExitCode -ge 8) { throw "robocopy failed with code $($copy.ExitCode)" }
Write-Host "Snapshot ready in $Snapshot. Indexed books: $($ids.Count - $missing.Count)"
