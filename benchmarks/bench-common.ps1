# Shared helpers for the benchmark scripts. Compatible with Windows PowerShell 5.1 and PowerShell 7.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:BenchDir = $PSScriptRoot
$script:RepoDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$script:Invariant = [Globalization.CultureInfo]::InvariantCulture
# Same heap for every service JVM in every experiment (see the benchmark plan).
$script:ServiceJvmOptions = "-Xms1G -Xmx4G"

function Split-List([object[]]$Values) {
    # powershell -File passes "1,4,16" as a single string, so both forms are accepted.
    return @($Values | ForEach-Object { "$_" -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
}

function Read-IdList([string]$Path) {
    # One id per line; anything after # is a comment.
    return @(Get-Content -LiteralPath $Path | ForEach-Object { ($_ -split "#")[0].Trim() } |
        Where-Object { $_ -ne "" } | ForEach-Object { [int]$_ })
}

function Format-Number([double]$Value, [string]$Format = "0.###") {
    # Decimal point regardless of the Windows language, so the CSV files parse everywhere.
    return $Value.ToString($Format, $script:Invariant)
}

function Invoke-Native([scriptblock]$Command) {
    # Windows PowerShell 5.1 turns stderr of native programs into errors when the preference is Stop.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $Command 2>&1 | ForEach-Object { "$_" })
        return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Use-Java([string]$JavaExe) {
    # Puts the given JDK first, for Maven, the services and the load client.
    if ([string]::IsNullOrWhiteSpace($JavaExe)) { return }
    $bin = Split-Path -Parent (Get-Item -LiteralPath $JavaExe).FullName
    $env:JAVA_HOME = Split-Path -Parent $bin
    $env:PATH = $bin + [IO.Path]::PathSeparator + $env:PATH
}

function New-ResultsDir([string]$OutDir) {
    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        $OutDir = Join-Path $script:BenchDir ("results\" + (Get-Date -Format "yyyy-MM-dd"))
    }
    New-Item -ItemType Directory -Path (Join-Path $OutDir "raw") -Force | Out-Null
    return (Resolve-Path -LiteralPath $OutDir).Path
}

function Save-HostInfo([string]$OutDir) {
    # Hardware and software of the run, saved next to the results.
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $os = Get-CimInstance Win32_OperatingSystem
    $java = Invoke-Native { java -version }
    $power = Invoke-Native { powercfg /getactivescheme }
    $git = Invoke-Native { git -C $script:RepoDir rev-parse HEAD }
    # One element per line (a comma would bind tighter than the + concatenation).
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("saved_at: " + (Get-Date).ToString("o"))
    $lines.Add("cpu: " + $cpu.Name.Trim())
    $lines.Add("cores: " + $cpu.NumberOfCores + " logical: " + $cpu.NumberOfLogicalProcessors)
    $lines.Add("ram_gb: " + (Format-Number ($os.TotalVisibleMemorySize / 1MB) "0.0"))
    $lines.Add("os: " + $os.Caption + " " + $os.Version)
    $lines.Add("power_scheme: " + ($power.Output -join " "))
    $lines.Add("java: " + ($java.Output -join " | "))
    $lines.Add("git_commit: " + ($git.Output -join ""))
    $lines.Add("service_jvm_options: " + $script:ServiceJvmOptions)
    $lines.Add("powershell: " + $PSVersionTable.PSVersion)
    Set-Content -LiteralPath (Join-Path $OutDir "host.txt") -Value $lines -Encoding UTF8
}

function Restore-Snapshot([string]$Snapshot, [string]$DataDir) {
    # Every run starts from the same data: the snapshot is mirrored into the working DataDir.
    if (-not (Test-Path -LiteralPath (Join-Path $Snapshot "datamarts\datamarts.db"))) {
        throw "$Snapshot is not a corpus snapshot (missing datamarts\datamarts.db). Run prepare-corpus.ps1 first."
    }
    New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
    $copy = Invoke-Native { robocopy $Snapshot $DataDir /MIR /R:2 /W:1 /NFL /NDL /NJH /NJS /NP /XD logs /XF run-config.json pids.txt deployment.lock }
    if ($copy.ExitCode -ge 8) { throw "robocopy failed with code $($copy.ExitCode): $($copy.Output -join ' ')" }
    foreach ($name in @("run-config.json", "pids.txt")) {
        Remove-Item -LiteralPath (Join-Path $DataDir $name) -ErrorAction SilentlyContinue
    }
}

function Start-Deployment([hashtable]$Parameters) {
    # start-all.ps1 passes its environment to the services: JAVA_TOOL_OPTIONS fixes their heap.
    $saved = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = $script:ServiceJvmOptions
    try {
        return & (Join-Path $script:RepoDir "scripts\start-all.ps1") @Parameters
    } finally {
        $env:JAVA_TOOL_OPTIONS = $saved
    }
}

function Stop-Deployment([string]$DataDir) {
    try { & (Join-Path $script:RepoDir "scripts\stop-all.ps1") -DataDir $DataDir }
    catch { Write-Warning ("stop-all: " + $_.Exception.Message) }
}

function Save-RunConfig([string]$DataDir, [string]$Target) {
    $source = Join-Path $DataDir "run-config.json"
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $Target -Force }
}

function Start-Monitor([string]$DataDir, [string]$Out) {
    # monitor.ps1 runs as its own process and writes one line per process and second.
    $stopFile = "$Out.stop"
    Remove-Item -LiteralPath $stopFile -ErrorAction SilentlyContinue
    $shell = (Get-Process -Id $PID).Path
    $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"' + (Join-Path $script:BenchDir "monitor.ps1") + '"'),
        "-DataDir", ('"' + $DataDir + '"'), "-Out", ('"' + $Out + '"'), "-StopFile", ('"' + $stopFile + '"'))
    $process = Start-Process -FilePath $shell -ArgumentList $arguments -WindowStyle Hidden -PassThru
    return [pscustomobject]@{ Process = $process; StopFile = $stopFile }
}

function Stop-Monitor([object]$Monitor) {
    if ($null -eq $Monitor) { return }
    New-Item -ItemType File -Path $Monitor.StopFile -Force | Out-Null
    if (-not $Monitor.Process.WaitForExit(15000)) { Stop-Process -Id $Monitor.Process.Id -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $Monitor.StopFile -ErrorAction SilentlyContinue
}

function Wait-ControlIdle([string]$ControlUrl, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        $status = Invoke-RestMethod -Uri "$ControlUrl/control/status"
        if ($status.active -eq 0) { return $status }
        if ((Get-Date) -gt $deadline) { throw "Timed out with $($status.active) books still active." }
        Start-Sleep -Milliseconds 250
    }
}

function Get-ControlBooks([string]$ControlUrl, [string]$State = "") {
    $books = @()
    $offset = 0
    while ($true) {
        $uri = "$ControlUrl/control/books?limit=500&offset=$offset"
        if ($State) { $uri += "&state=$State" }
        $page = Invoke-RestMethod -Uri $uri
        $books += @($page.books)
        $offset += 500
        if ($offset -ge $page.total) { break }
    }
    return $books
}

function Add-CsvLine([string]$Path, [string]$Header, [string[]]$Values) {
    if (-not (Test-Path -LiteralPath $Path)) { Set-Content -LiteralPath $Path -Value $Header -Encoding ASCII }
    $escaped = $Values | ForEach-Object { if ("$_" -match '[,"]') { '"' + ("$_" -replace '"', '""') + '"' } else { "$_" } }
    Add-Content -LiteralPath $Path -Value ($escaped -join ",") -Encoding ASCII
}
