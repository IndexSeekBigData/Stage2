<#
.SYNOPSIS
  Repeatable sample ingestion against a running Ingestion Service (Windows PowerShell 5+ or PowerShell 7).

.EXAMPLE
  java -jar target\ingestion-service-1.0.0.jar      # in another window
  .\scripts\sample-ingestion.ps1                      # default books
  .\scripts\sample-ingestion.ps1 -Books 11,84,1342 -BaseUrl http://localhost:7001
#>
param(
    [string]$BaseUrl = "http://localhost:7001",
    [int[]]$Books = @(11, 84, 345, 1342, 2701)
)

$ErrorActionPreference = "Stop"

function Invoke-Api([string]$Method, [string]$Path) {
    try {
        $r = Invoke-WebRequest -Uri "$BaseUrl$Path" -Method $Method -UseBasicParsing
        return @{ Status = [int]$r.StatusCode; Body = ($r.Content | ConvertFrom-Json) }
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        # Windows PowerShell 5.1 has already consumed the response stream and keeps the text in ErrorDetails.
        $text = $_.ErrorDetails.Message
        if (-not $text) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $reader.ReadToEnd()
        }
        return @{ Status = [int]$resp.StatusCode; Body = ($text | ConvertFrom-Json) }
    } catch {
        if ($_.Exception.Response) {
            return @{ Status = [int]$_.Exception.Response.StatusCode; Body = ($_.ErrorDetails.Message | ConvertFrom-Json) }
        }
        throw
    }
}

$failures = 0
function Check([string]$Name, [bool]$Ok, [string]$Detail) {
    if ($Ok) { Write-Host "PASS  $Name  $Detail" -ForegroundColor Green }
    else     { Write-Host "FAIL  $Name  $Detail" -ForegroundColor Red; $script:failures++ }
}

$health = Invoke-Api GET "/health"
Check "health" ($health.Status -eq 200 -and $health.Body.status -eq "UP") "max_threads=$($health.Body.max_threads)"

$times = @()
foreach ($id in $Books) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-Api POST "/ingest/$id"
    $sw.Stop()
    $times += $sw.Elapsed.TotalMilliseconds
    Check "ingest $id" ($r.Status -eq 200) ("status={0} path={1} body_bytes={2} {3:N0} ms" -f $r.Body.status, $r.Body.path, $r.Body.body_bytes, $sw.Elapsed.TotalMilliseconds)
}

foreach ($id in $Books) {
    $r = Invoke-Api POST "/ingest/$id"
    Check "re-ingest $id is idempotent" ($r.Status -eq 200 -and $r.Body.status -eq "available") "status=$($r.Body.status)"
}

$s = Invoke-Api GET "/ingest/status/$($Books[0])"
Check "status available" ($s.Status -eq 200 -and $s.Body.status -eq "available") ""

$s = Invoke-Api GET "/ingest/status/9999998"
Check "status not_available" ($s.Status -eq 404 -and $s.Body.status -eq "not_available") ""

$l = Invoke-Api GET "/ingest/list"
$missing = $Books | Where-Object { $l.Body.books -notcontains $_ }
Check "list contains all sample books" ($l.Status -eq 200 -and -not $missing) "count=$($l.Body.count)"

$e = Invoke-Api POST "/ingest/abc"
Check "invalid id -> 400" ($e.Status -eq 400 -and $e.Body.error -eq "invalid_book_id") ""

$e = Invoke-Api POST "/ingest/9999999"
Check "unknown book -> 404" ($e.Status -eq 404 -and $e.Body.error -eq "book_not_found") ""

Write-Host ""
Write-Host ("First-time ingestion: mean {0:N0} ms over {1} books" -f (($times | Measure-Object -Average).Average), $times.Count)
if ($failures -gt 0) { Write-Host "$failures check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "All checks passed" -ForegroundColor Green
