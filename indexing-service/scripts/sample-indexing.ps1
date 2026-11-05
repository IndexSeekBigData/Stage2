<#
.SYNOPSIS
  Repeatable checks against a running Indexing Service (Windows PowerShell 5+ or PowerShell 7).
  The book must have been ingested first.

.EXAMPLE
  .\scripts\sample-indexing.ps1
  .\scripts\sample-indexing.ps1 -BookId 84 -BaseUrl http://localhost:7002
#>
param(
    [string]$BaseUrl = "http://localhost:7002",
    [int]$BookId = 1342
)

$ErrorActionPreference = "Stop"

function Invoke-Api([string]$Method, [string]$Path) {
    try {
        $r = Invoke-WebRequest -Uri "$BaseUrl$Path" -Method $Method -UseBasicParsing
        return @{ Status = [int]$r.StatusCode; Body = ($r.Content | ConvertFrom-Json) }
    } catch {
        if (-not $_.Exception.Response) { throw }
        # Windows PowerShell 5.1 has already consumed the response stream and keeps the text in ErrorDetails.
        return @{ Status = [int]$_.Exception.Response.StatusCode; Body = ($_.ErrorDetails.Message | ConvertFrom-Json) }
    }
}

$failures = 0
function Check([string]$Name, [bool]$Ok, [string]$Detail) {
    if ($Ok) { Write-Host "PASS  $Name  $Detail" -ForegroundColor Green }
    else     { Write-Host "FAIL  $Name  $Detail" -ForegroundColor Red; $script:failures++ }
}

$health = Invoke-Api GET "/health"
Check "health" ($health.Status -eq 200 -and $health.Body.status -eq "UP") "max_threads=$($health.Body.max_threads)"

$r = Invoke-Api POST "/index/update/$BookId"
Check "update $BookId" ($r.Status -eq 200 -and $r.Body.index -eq "updated" -and $r.Body.book_id -eq $BookId) "index=$($r.Body.index)"

$first = (Invoke-Api GET "/index/status").Body
$r = Invoke-Api POST "/index/update/$BookId"
$second = (Invoke-Api GET "/index/status").Body
Check "repeated update is idempotent" ($r.Status -eq 200 -and $first.books_indexed -eq $second.books_indexed) "books_indexed=$($second.books_indexed) index_version=$($second.index_version)"

$e = Invoke-Api POST "/index/update/9999999"
Check "book not ingested -> 404" ($e.Status -eq 404 -and $e.Body.error -eq "book_not_found") ""

$e = Invoke-Api POST "/index/update/abc"
Check "invalid id -> 400" ($e.Status -eq 400 -and $e.Body.error -eq "invalid_book_id") ""

$r = Invoke-Api POST "/index/rebuild"
Check "rebuild" ($r.Status -eq 200 -and $r.Body.books_processed -ge 1) "books_processed=$($r.Body.books_processed) elapsed=$($r.Body.elapsed_time)"

Write-Host ""
if ($failures -gt 0) { Write-Host "$failures check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "All checks passed" -ForegroundColor Green
