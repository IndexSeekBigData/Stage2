<#
.SYNOPSIS
Read-only, repeatable Search checks against an already running service.
Requires an indexed BookId and at least one result for Query.
#>
param(
    [string]$BaseUrl = "http://localhost:7003",
    [int]$BookId = 1342,
    [string]$Query = "love",
    [string]$SecondTerm = "truth",
    [string]$EvidencePath = (Join-Path $PSScriptRoot "..\..\data\search-validation")
)

$ErrorActionPreference = "Stop"
$checks = [System.Collections.Generic.List[object]]::new()
$failures = 0

function Invoke-Api([string]$Path) {
    try {
        $response = Invoke-WebRequest -Uri "$BaseUrl$Path" -Method GET -UseBasicParsing
        $result = @{ Path = $Path; Status = [int]$response.StatusCode; Body = ($response.Content | ConvertFrom-Json) }
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        $bodyText = $_.ErrorDetails.Message
        if (-not $bodyText -and $response.PSObject.Methods.Name -contains "GetResponseStream") {
            $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
            try { $bodyText = $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
        $result = @{ Path = $Path; Status = [int]$response.StatusCode; Body = ($bodyText | ConvertFrom-Json) }
    }
    $checks.Add($result)
    return $result
}

function Check([string]$Name, [bool]$Passed) {
    if ($Passed) { Write-Host "PASS $Name" -ForegroundColor Green }
    else { Write-Host "FAIL $Name" -ForegroundColor Red; $script:failures++ }
}

function Encode([string]$Value) { return [Uri]::EscapeDataString($Value) }

$encoded = Encode $Query
$health = Invoke-Api "/health"
Check "health" ($health.Status -eq 200 -and $health.Body.status -eq "UP")

$single = Invoke-Api "/search?q=$encoded"
Check "keyword search" ($single.Status -eq 200 -and $single.Body.total -gt 0)
$multiple = Invoke-Api ("/search?q=" + (Encode "$Query $SecondTerm"))
Check "AND search" ($multiple.Status -eq 200)

$book = Invoke-Api "/books/$BookId"
Check "book detail" ($book.Status -eq 200 -and $book.Body.book_id -eq $BookId)
if ($book.Status -eq 200) {
    if ($book.Body.author) {
        $author = Encode $book.Body.author
        $filtered = Invoke-Api "/search?q=$encoded&author=$author"
        Check "author filter" ($filtered.Status -eq 200)
    }
    if ($book.Body.language) {
        $language = Encode $book.Body.language
        $filtered = Invoke-Api "/search?q=$encoded&language=$language"
        $wrongLanguage = @($filtered.Body.results | Where-Object { $_.language -ne $book.Body.language })
        Check "language filter" ($filtered.Status -eq 200 -and $wrongLanguage.Count -eq 0)
        if ($book.Body.author) {
            $combined = Invoke-Api "/search?q=$encoded&author=$author&language=$language"
            Check "combined filters" ($combined.Status -eq 200)
        }
    }
}
$year = Invoke-Api "/search?q=$encoded&year=1813"
$wrongYear = @($year.Body.results | Where-Object { $_.year -ne 1813 })
Check "year filter including absent years" ($year.Status -eq 200 -and $wrongYear.Count -eq 0)

$first = Invoke-Api "/search?q=$encoded&offset=0&limit=1"
$second = Invoke-Api "/search?q=$encoded&offset=1&limit=1"
Check "pagination status" ($first.Status -eq 200 -and $second.Status -eq 200)
if ($first.Body.index_version -eq $second.Body.index_version) {
    Check "pagination total" ($first.Body.total -eq $second.Body.total)
    $ids = @($first.Body.results | ForEach-Object { $_.book_id }) + @($second.Body.results | ForEach-Object { $_.book_id })
    Check "pages have no duplicate ids" (@($ids | Select-Object -Unique).Count -eq $ids.Count)
} else {
    Write-Host "Index changed between page requests; stable traversal must restart."
}
$beyond = Invoke-Api "/search?q=$encoded&offset=2147483647"
Check "offset beyond total" ($beyond.Status -eq 200 -and @($beyond.Body.results).Count -eq 0)
$empty = Invoke-Api "/search?q=nonexistentinverseekterm"
Check "no matches" ($empty.Status -eq 200 -and $empty.Body.total -eq 0)
$badQuery = Invoke-Api "/search?q=a"
Check "invalid query" ($badQuery.Status -eq 400 -and $badQuery.Body.error -eq "invalid_query")
$badLimit = Invoke-Api "/search?q=$encoded&limit=101"
Check "invalid limit" ($badLimit.Status -eq 400 -and $badLimit.Body.error -eq "invalid_parameter")
$missing = Invoke-Api "/missing"
Check "unknown route JSON" ($missing.Status -eq 404 -and $missing.Body.error -eq "route_not_found")
$filters = Invoke-Api "/search/filters"
Check "filter inventory" ($filters.Status -eq 200 -and $filters.Body.books_indexed -ge 1)

New-Item -ItemType Directory -Path $EvidencePath -Force | Out-Null
$checks | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $EvidencePath "search-checks.json") -Encoding utf8
Write-Host "Evidence: $EvidencePath"
if ($failures -gt 0) { throw "$failures Search checks failed" }
Write-Host "All Search checks passed" -ForegroundColor Green
