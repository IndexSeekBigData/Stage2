<#
.SYNOPSIS
Starts one Control, one Indexing and configurable Ingestion/Search replicas on one Windows host.
No build, download registration or index rebuild is performed by this script.
#>
[CmdletBinding()]
param(
    [string]$DataDir = (Join-Path $PSScriptRoot "..\data"),
    [ValidateRange(1,8)][int]$IngestionReplicas = 1,
    [ValidateRange(1,8)][int]$SearchReplicas = 1,
    [ValidateRange(1,64)][int]$ControlWorkers = 4,
    [ValidateRange(0,4096)][int]$ServerThreads = 0,
    [ValidateRange(0,50000)][int]$PortOffset = 0,
    [ValidateRange(5,300)][int]$StartupTimeoutSeconds = 60,
    [string]$JavaExe = "",
    [string]$GutenbergBaseUrl = "https://www.gutenberg.org",
    [string]$CorsAllowedOrigins = "*"
)
. (Join-Path $PSScriptRoot "deployment-common.ps1")
$DataDir = Get-DeploymentDataDir $DataDir
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
if ($ServerThreads -gt 0 -and $ServerThreads -lt 8) { throw "ServerThreads must be 0 or at least 8; Jetty may require more for its connectors." }
$source = [uri]$GutenbergBaseUrl
if (-not $source.IsAbsoluteUri -or $source.Scheme -notin @("http","https") -or -not $source.Host) { throw "GutenbergBaseUrl must be an absolute HTTP(S) URL." }
$java = Resolve-DeploymentJava $JavaExe
$specs = @()
for ($i=0; $i -lt $IngestionReplicas; $i++) {
    $specs += [pscustomobject]@{ service="ingestion"; instance=$i; port=(7001 + $PortOffset + 10*$i) }
}
$specs += [pscustomobject]@{ service="indexing"; instance=0; port=(7002 + $PortOffset) }
for ($i=0; $i -lt $SearchReplicas; $i++) {
    $specs += [pscustomobject]@{ service="search"; instance=$i; port=(7003 + $PortOffset + 10*$i) }
}
$specs += [pscustomobject]@{ service="control"; instance=0; port=(7000 + $PortOffset) }
# Validate all artifacts and ports before starting any process.
foreach ($spec in $specs) {
    $module = $spec.service + "-service"
    if ($spec.service -eq "control") { $module = "control-module" }
    $jar = Join-Path $repository ($module + "\target\" + $module + "-1.0.0.jar")
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Missing $jar. Run mvn clean package with Java 17 first." }
}
$guard = Enter-DeploymentLock $DataDir
$config = $null
try {
    $previous = Read-DeploymentConfig $DataDir
    if ($null -ne $previous -and $previous.status -ne "stopped") {
        foreach ($record in @($previous.processes | Where-Object { $_.status -eq "running" })) {
            if ($null -ne (Get-ManagedProcess $record $previous)) {
                throw "A managed deployment is already running. Use stop-all.ps1 -DataDir '$DataDir' first."
            }
        }
    }
    foreach ($spec in $specs) { Assert-DeploymentPort $spec.port }
    $runId = [guid]::NewGuid().ToString("N")
    $logs = Join-Path $DataDir ("logs\" + $runId)
    New-Item -ItemType Directory -Path $logs -Force | Out-Null
    $ingestionUrls = @($specs | Where-Object { $_.service -eq "ingestion" } | ForEach-Object { "http://localhost:" + $_.port })
    $searchUrls = @($specs | Where-Object { $_.service -eq "search" } | ForEach-Object { "http://localhost:" + $_.port })
    $config = [pscustomobject]@{
        schema_version=1; run_id=$runId; status="starting"; started_at=[datetime]::UtcNow.ToString("o"); stopped_at=$null
        repository=$repository; data_dir=$DataDir; log_dir=$logs; java_executable=$java.Path; java_version=$java.Version
        ingestion_replicas=$IngestionReplicas; search_replicas=$SearchReplicas; control_workers=$ControlWorkers
        server_threads=$ServerThreads; port_offset=$PortOffset; source_url=$GutenbergBaseUrl; cors_allowed_origins=$CorsAllowedOrigins
        endpoints=[pscustomobject]@{ control=("http://localhost:"+(7000+$PortOffset)); indexing=("http://localhost:"+(7002+$PortOffset)); ingestion=$ingestionUrls; search=$searchUrls }
        processes=@(); error=$null
    }
    Save-DeploymentConfig $config
    foreach ($spec in $specs) {
        $settings = @{
            DATALAKE_PATH=(Join-Path $DataDir "datalake")
            DATAMARTS_PATH=(Join-Path $DataDir "datamarts\datamarts.db")
            CONTROL_DB_PATH=(Join-Path $DataDir "control\control.db")
            SERVER_MAX_THREADS="$ServerThreads"
        }
        switch ($spec.service) {
            "ingestion" {
                $settings.INGESTION_PORT="$($spec.port)"
                $settings.GUTENBERG_BASE_URL=$GutenbergBaseUrl
                $settings.DOWNLOAD_TIMEOUT_SECONDS="60"
                $settings.DOWNLOAD_MAX_BYTES="52428800"
                $settings.DATALAKE_PARTITION_ZONE="UTC"
            }
            "indexing" { $settings.INDEXING_PORT="$($spec.port)" }
            "search" {
                $settings.SEARCH_PORT="$($spec.port)"
                $settings.SEARCH_DEFAULT_LIMIT="20"; $settings.SEARCH_MAX_LIMIT="100"
                $settings.SQLITE_BUSY_TIMEOUT_MS="5000"; $settings.CORS_ALLOWED_ORIGINS=$CorsAllowedOrigins
            }
            "control" {
                $settings.CONTROL_PORT="$($spec.port)"; $settings.CONTROL_WORKERS="$ControlWorkers"
                $settings.INGESTION_URL=($ingestionUrls -join ","); $settings.INDEXING_URL=$config.endpoints.indexing
                $settings.CONTROL_MAX_ATTEMPTS="3"; $settings.INGESTION_TIMEOUT_SECONDS="90"; $settings.INDEXING_TIMEOUT_SECONDS="120"
                $settings.CONTROL_LEASE_INGEST_SECONDS="120"; $settings.CONTROL_LEASE_INDEX_SECONDS="180"
                $settings.CONTROL_RETRY_DELAY_SECONDS="2"; $settings.CONTROL_REAPER_SECONDS="10"
                $settings.CONTROL_IDLE_MILLIS="500"; $settings.CONTROL_MAX_BACKOFF_SECONDS="10"
            }
        }
        $record = Start-ManagedService $spec $config $settings
        $config.processes += $record
        Save-DeploymentConfig $config
        Wait-ManagedService $record $StartupTimeoutSeconds $ServerThreads
        Save-DeploymentConfig $config
        Write-Host ("Ready {0}[{1}] PID={2} {3} max_threads={4}" -f $record.service,$record.instance,$record.pid,$record.url,$record.health.max_threads)
    }
    $config.status="running"
    Save-DeploymentConfig $config
    Write-Host "Configuration: $(Join-Path $DataDir 'run-config.json')"
    return $config
} catch {
    if ($null -ne $config) {
        $config.error=$_.Exception.Message
        # Roll back only processes started by this invocation, with identity checks.
        $records=@($config.processes)
        [array]::Reverse($records)
        foreach ($record in $records) {
            try {
                $managed=Get-ManagedProcess $record $config
                if ($null -ne $managed) { Stop-Process -Id $managed.Id; [void]$managed.WaitForExit(10000) }
                $record.status="stopped"
            } catch { Write-Warning $_.Exception.Message }
        }
        $config.status="failed"
        Save-DeploymentConfig $config
    }
    throw
} finally { $guard.Dispose() }
