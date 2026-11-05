<#
.SYNOPSIS
  Registers books, waits until none is active, prints a summary and runs a few searches.
  It starts nothing: Ingestion, Indexing, Search and Control must already be running.
  Works on Windows PowerShell 5+ and PowerShell 7.

.EXAMPLE
  .\scripts\run-pipeline.ps1
  .\scripts\run-pipeline.ps1 -Ids 11,84,1342,9999999 -Queries love,whale
#>
param(
    [string]$ControlUrl = "http://localhost:7000",
    [string]$SearchUrl = "http://localhost:7003",
    [string[]]$Ids = @("11", "84", "345", "1342", "2701"),
    [string[]]$Queries = @("love", "whale"),
    [int]$TimeoutSeconds = 600,
    [int]$PollSeconds = 2
)

$ErrorActionPreference = "Stop"
# powershell -File passes "11,84" as one string, so comma-separated values are accepted in both forms.
$bookIds = @($Ids | ForEach-Object { $_ -split "," } | ForEach-Object { [int]$_ })
$Queries = @($Queries | ForEach-Object { $_ -split "," })
$body = @{ ids = $bookIds } | ConvertTo-Json -Compress

# The first request is retried too, so the script can start while a service restarts.
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ($true) {
    try {
        $registered = Invoke-RestMethod -Uri "$ControlUrl/control/books" -Method Post -Body $body -ContentType "application/json"
        break
    } catch {
        if ((Get-Date) -gt $deadline) { throw }
        Start-Sleep -Seconds $PollSeconds
    }
}
Write-Host "Registered: inserted=$($registered.inserted) ignored=$($registered.ignored)"

$status = $null
$last = ""
while ($true) {
    try {
        $status = Invoke-RestMethod -Uri "$ControlUrl/control/status"
        $line = "active=$($status.active) states=$($status.states | ConvertTo-Json -Compress)"
        if ($line -ne $last) { Write-Host ("{0:HH:mm:ss} {1}" -f (Get-Date), $line); $last = $line }
        if ($status.active -eq 0) { break }
    } catch {
        Write-Host ("{0:HH:mm:ss} Control is not responding: {1}" -f (Get-Date), $_.Exception.Message)
    }
    if ((Get-Date) -gt $deadline) {
        Write-Host "Timed out with books still active" -ForegroundColor Red
        exit 1
    }
    Start-Sleep -Seconds $PollSeconds
}

Write-Host ""
Write-Host "Summary"
$status.states.PSObject.Properties | ForEach-Object { Write-Host ("  {0,-12} {1}" -f $_.Name, $_.Value) }

foreach ($state in @("NOT_FOUND", "FAILED")) {
    if ($status.states.$state -gt 0) {
        $page = Invoke-RestMethod -Uri "$ControlUrl/control/books?state=$state&limit=500"
        foreach ($book in $page.books) { Write-Host "  $state $($book.book_id): $($book.last_error)" }
    }
}

$metrics = Invoke-RestMethod -Uri "$ControlUrl/control/metrics"
Write-Host ""
Write-Host "Metrics: $($metrics | ConvertTo-Json -Compress)"

Write-Host ""
Write-Host "Searches"
foreach ($query in $Queries) {
    $result = Invoke-RestMethod -Uri "$SearchUrl/search?q=$([uri]::EscapeDataString($query))&limit=3"
    $titles = ($result.results | ForEach-Object { $_.title }) -join " | "
    Write-Host "  '$query': total=$($result.total) top: $titles"
}
