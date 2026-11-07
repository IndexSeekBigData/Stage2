<#
.SYNOPSIS
Processes the same twenty books with one replica and with two Ingestion/three Search replicas.
Uses new isolated data directories; never deletes an existing corpus.
#>
[CmdletBinding()]
param(
    [string]$DataDir = (Join-Path $PSScriptRoot "..\data\deployment-validation"),
    [string]$JavaExe = "",
    [string]$GutenbergBaseUrl = "https://www.gutenberg.org",
    [ValidateRange(0,50000)][int]$PortOffset = 0,
    [ValidateRange(8,4096)][int]$ServerThreads = 32,
    [ValidateRange(30,3600)][int]$TimeoutSeconds = 600
)
. (Join-Path $PSScriptRoot "deployment-common.ps1")
$DataDir = Get-DeploymentDataDir $DataDir
$ids = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot "..\docs\deployment\demo-book-ids.txt") |
    Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ })
if ($ids.Count -ne 20 -or @($ids | Select-Object -Unique).Count -ne 20) { throw "The demonstration requires twenty distinct ids." }
$queries = @("love","love truth","adventure","whale","alice","love&language=en","love&author=austen","love&year=1813")
$run = Join-Path $DataDir ([datetime]::UtcNow.ToString("yyyyMMddTHHmmss") + "-" + [guid]::NewGuid().ToString("N").Substring(0,8))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$results = @()
try {
    foreach ($scenario in @(
        [pscustomobject]@{name="single"; ingestion=1; search=1},
        [pscustomobject]@{name="replicated"; ingestion=2; search=3}
    )) {
        $scenarioDir = Join-Path $run $scenario.name
        $owned = $false
        try {
            $deployment = & (Join-Path $PSScriptRoot "start-all.ps1") -DataDir $scenarioDir -JavaExe $JavaExe -GutenbergBaseUrl $GutenbergBaseUrl -PortOffset $PortOffset -IngestionReplicas $scenario.ingestion -SearchReplicas $scenario.search -ControlWorkers 4 -ServerThreads $ServerThreads
            $owned = $true
            $body = @{ids=$ids} | ConvertTo-Json -Compress
            $registration = Invoke-RestMethod -Uri ($deployment.endpoints.control + "/control/books") -Method Post -ContentType "application/json" -Body $body -TimeoutSec 10
            if ($registration.inserted -ne 20) { throw "Fresh scenario did not register twenty books." }
            $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
            while ($true) {
                $state = Invoke-RestMethod -Uri ($deployment.endpoints.control + "/control/status") -TimeoutSec 10
                Write-Host "$($scenario.name): active=$($state.active) indexed=$($state.states.INDEXED)"
                if ($state.states.FAILED -gt 0 -or $state.states.NOT_FOUND -gt 0) { throw "A demonstration book failed or was unavailable. Inspect Control and the service logs." }
                if ($state.active -eq 0) { break }
                if ([datetime]::UtcNow -gt $deadline) { throw "Timed out waiting for the corpus." }
                Start-Sleep -Seconds 1
            }
            if ($state.states.INDEXED -ne 20) { throw "Not every book reached INDEXED." }
            $searchUrls = @($deployment.endpoints.search)
            $answers = [ordered]@{}
            $requestNumber = 0
            foreach ($query in $queries) {
                $parts = $query -split '&',2
                $path = "/search?q=" + [uri]::EscapeDataString($parts[0]) + "&limit=100"
                if ($parts.Count -eq 2) { $path += "&" + $parts[1] }
                # The client rotates its initial target, then checks every replica.
                $reference = $null
                $version = $null
                for ($i=0; $i -lt $searchUrls.Count; $i++) {
                    $url = $searchUrls[($requestNumber+$i)%$searchUrls.Count]
                    $page = Invoke-RestMethod -Uri ($url+$path) -TimeoutSec 10
                    $canonical = [ordered]@{query=$page.query; filters=$page.filters; total=$page.total; offset=$page.offset; limit=$page.limit; results=@($page.results)}
                    $encoded = $canonical | ConvertTo-Json -Depth 20 -Compress
                    if ($null -eq $reference) { $reference=$encoded; $version=$page.index_version }
                    elseif ($reference -cne $encoded -or $version -ne $page.index_version) { throw "Search replicas disagree for $query." }
                }
                $answers[$query]=$reference
                $requestNumber++
            }
            foreach ($url in $searchUrls) {
                foreach ($id in $ids) {
                    $book=Invoke-RestMethod -Uri ($url+"/books/"+$id) -TimeoutSec 10
                    if ($book.book_id -ne $id) { throw "Missing book $id on $url." }
                }
            }
            $markers=@(Get-ChildItem -LiteralPath (Join-Path $scenarioDir "datalake") -Filter "*.success.json" -Recurse -File)
            if ($markers.Count -ne 20) { throw "Unexpected number of completion markers." }
            $markerIds=@()
            foreach ($marker in $markers) {
                $record=Get-Content -LiteralPath $marker.FullName -Raw | ConvertFrom-Json
                $markerIds += $record.bookId
                $bodyFile=Join-Path (Join-Path $scenarioDir "datalake") $record.bodyPath
                if ((Get-DeploymentSha256 $bodyFile) -ne $record.bodySha256) { throw "Body hash mismatch." }
            }
            if (@($markerIds | Select-Object -Unique).Count -ne 20) { throw "Duplicate published ids." }
            if (@(Get-ChildItem -LiteralPath (Join-Path $scenarioDir "datalake\.tmp") -Force).Count -ne 0) { throw "Temporary downloads remain." }
            $health=@($deployment.processes | ForEach-Object {Invoke-RestMethod -Uri ($_.url+"/health") -TimeoutSec 10})
            $ingestionHealth=@($health | Where-Object {$_.service -eq "ingestion"})
            if (@($ingestionHealth | Where-Object {$_.downloads_since_start -gt 0}).Count -ne $scenario.ingestion) { throw "An Ingestion replica received no downloads." }
            $results += [pscustomobject]@{scenario=$scenario.name; data_dir=$scenarioDir; source_url=$GutenbergBaseUrl; indexed=20; marker_count=20; answers=$answers; health=$health}
        } finally {
            if ($owned) { & (Join-Path $PSScriptRoot "stop-all.ps1") -DataDir $scenarioDir }
        }
    }
    foreach ($query in $queries) {
        if ($results[0].answers[$query] -cne $results[1].answers[$query]) {
            throw "Single and replicated deployments differ for $query. Check corpus content and logs."
        }
    }
    [pscustomobject]@{status="passed"; source_url=$GutenbergBaseUrl; ids=$ids; scenarios=$results} |
        ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $run "comparison.json") -Encoding UTF8
    Write-Host "Replica comparison passed. Evidence: $run"
    return $run
} catch {
    [pscustomobject]@{status="failed"; source_url=$GutenbergBaseUrl; error=$_.Exception.Message; completed=$results} |
        ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $run "comparison.json") -Encoding UTF8
    throw
}
