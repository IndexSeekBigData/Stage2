<#
.SYNOPSIS
Runs the JMH microbenchmarks of Ingestion, Indexing and Search and collects their CSV results.

.DESCRIPTION
Each benchmark runs from the module's test classpath through exec:exec, so the JVMs that JMH forks
get the same classpath (with exec:java the forks fail with ClassNotFoundException: ForkedMain).
Indexing and Search benchmarks are repeated with each -Threads value (JMH -t); Ingestion runs with
one thread. Results: <OutDir>\jmh\jmh-<module>-<benchmark>-t<threads>.csv plus the JMH log (.txt).

.EXAMPLE
.\benchmarks\run-jmh.ps1 -Datamart C:\BD\bench\snapshot\datamarts\datamarts.db
.\benchmarks\run-jmh.ps1 -Modules search -Threads 1,8 -Quick
#>
[CmdletBinding()]
param(
    [string[]]$Modules = @("ingestion", "indexing", "search"),
    [string[]]$Threads = @("1", "2", "4", "8"),
    # Corpus datamart for SearchBenchmark; without it the benchmark builds a synthetic index.
    [string]$Datamart = "",
    [string]$OutDir = "",
    # Short iterations to check that everything runs. Never use these numbers in the report.
    [switch]$Quick,
    [string]$JavaExe = ""
)
. (Join-Path $PSScriptRoot "bench-common.ps1")
Use-Java $JavaExe
$OutDir = New-ResultsDir $OutDir
$jmhDir = Join-Path $OutDir "jmh"
New-Item -ItemType Directory -Path $jmhDir -Force | Out-Null
Save-HostInfo $OutDir
$threadCounts = @(Split-List $Threads | ForEach-Object { [int]$_ })

$plan = @{
    ingestion = @{ Module = "ingestion-service"; Benchmarks = @("BookParserBenchmark", "DatalakeWriteBenchmark"); Threaded = $false }
    indexing  = @{ Module = "indexing-service"; Benchmarks = @("TextProcessingBenchmark", "SqliteWriteBenchmark"); Threaded = $true }
    search    = @{ Module = "search-service"; Benchmarks = @("SearchBenchmark"); Threaded = $true }
}
if ($Datamart) {
    if (-not (Test-Path -LiteralPath $Datamart -PathType Leaf)) { throw "Datamart not found: $Datamart" }
    $env:INVERSEEK_BENCH_DATAMART = (Resolve-Path -LiteralPath $Datamart).Path
} else {
    Remove-Item Env:\INVERSEEK_BENCH_DATAMART -ErrorAction SilentlyContinue
    Write-Warning "No -Datamart: SearchBenchmark uses its synthetic index."
}
$extra = if ($Quick) { " -wi 1 -i 1 -w 1s -r 1s" } else { "" }

$failedRuns = 0
foreach ($name in (Split-List $Modules)) {
    if (-not $plan.ContainsKey($name)) { throw "Unknown module '$name'. Use ingestion, indexing or search." }
    $entry = $plan[$name]
    $moduleDir = Join-Path $script:RepoDir $entry.Module
    $counts = if ($entry.Threaded) { $threadCounts } else { @(1) }
    Push-Location $moduleDir
    try {
        Write-Host "[$name] compiling benchmarks"
        $build = Invoke-Native { mvn -q -B test-compile }
        if ($build.ExitCode -ne 0) { throw "mvn test-compile failed in ${moduleDir}:`n$($build.Output -join "`n")" }
        foreach ($benchmark in $entry.Benchmarks) {
            foreach ($count in $counts) {
                $base = "jmh-$name-$benchmark-t$count"
                $jmhArgs = "-cp %classpath org.openjdk.jmh.Main $benchmark -foe true -t $count -rf csv -rff target/$base.csv -o target/$base.txt$extra"
                Write-Host ("[{0}] {1} threads={2} ({3:HH:mm:ss})" -f $name, $benchmark, $count, (Get-Date))
                $run = Invoke-Native { mvn -q -B exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=test" "-Dexec.args=$jmhArgs" }
                if (Test-Path -LiteralPath "target\$base.txt") { Move-Item -LiteralPath "target\$base.txt" -Destination $jmhDir -Force }
                if ($run.ExitCode -ne 0 -or -not (Test-Path -LiteralPath "target\$base.csv")) {
                    $failedRuns++
                    Write-Warning "$benchmark failed (exit $($run.ExitCode)). Log: $(Join-Path $jmhDir "$base.txt")`n$($run.Output -join "`n")"
                    continue
                }
                Move-Item -LiteralPath "target\$base.csv" -Destination $jmhDir -Force
                Get-Content -LiteralPath (Join-Path $jmhDir "$base.txt") | Select-String -Pattern "^Benchmark|^\S+Benchmark\." | ForEach-Object { Write-Host "    $_" }
            }
        }
    } finally {
        Pop-Location
    }
}
Write-Host "JMH results in $jmhDir"

if ($failedRuns -gt 0) { throw "$failedRuns JMH runs failed; inspect their logs" }
