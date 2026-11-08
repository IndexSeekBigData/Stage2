[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$DataDir,
    [Parameter(Mandatory=$true)][ValidateSet("control","ingestion","indexing","search")][string]$Service,
    [Parameter(Mandatory=$true)][ValidateSet("Stop","Start","Restart")][string]$Action,
    [Parameter(Mandatory=$true)][string]$ValidationToken,
    [int]$Instance=-1,
    [string]$OverridesFile=""
)
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
. (Join-Path $root "scripts\deployment-common.ps1")
$DataDir=Get-DeploymentDataDir $DataDir
$guard=Enter-DeploymentLock $DataDir
try {
    $config=Read-DeploymentConfig $DataDir
    $owner=Get-Content -LiteralPath (Join-Path $DataDir "validation-owner.json") -Raw | ConvertFrom-Json
    if($owner.token -cne $ValidationToken -or $owner.run_id -ne $config.run_id -or $config.repository -ne $root) {throw "Validation deployment ownership mismatch"}
    $overrides=@{}
    if($OverridesFile) {
        $file=[IO.Path]::GetFullPath($OverridesFile)
        if(-not $file.StartsWith($DataDir+"\",[StringComparison]::OrdinalIgnoreCase)){throw "Overrides must be inside the validation directory"}
        $values=Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        $allowed=@("CONTROL_DB_PATH","CONTROL_WORKERS","INGESTION_TIMEOUT_SECONDS","INDEXING_TIMEOUT_SECONDS","CONTROL_LEASE_INGEST_SECONDS","CONTROL_LEASE_INDEX_SECONDS","CONTROL_REAPER_SECONDS","CONTROL_RETRY_DELAY_SECONDS","CONTROL_IDLE_MILLIS","CONTROL_MAX_BACKOFF_SECONDS")
        foreach($property in $values.PSObject.Properties) {
            if($Service -ne "control" -or $property.Name -notin $allowed) {throw "Unsupported validation override: $($property.Name)"}
            if($property.Name -eq "CONTROL_DB_PATH" -and -not ([IO.Path]::GetFullPath($property.Value)).StartsWith($DataDir+"\",[StringComparison]::OrdinalIgnoreCase)) {throw "Control database must stay inside validation data"}
            $overrides[$property.Name]=[string]$property.Value
        }
    }
    $selected=@($config.processes | Where-Object {$_.service -eq $Service -and ($Instance -lt 0 -or $_.instance -eq $Instance)})
    if($selected.Count -eq 0){throw "No selected managed service"}
    foreach($record in $selected) {
        $managed=Get-ManagedProcess $record $config
        if($Action -in @("Stop","Restart")) {
            if($null -ne $managed) {
                Stop-Process -Id $managed.Id -ErrorAction Stop
                if(-not $managed.WaitForExit(10000)){throw "Process did not stop"}
            }
            $record.status="stopped"
            Save-DeploymentConfig $config
        }
        if($Action -in @("Start","Restart")) {
            if($Action -eq "Start" -and $null -ne $managed){continue}
            $settings=@{}
            foreach($property in $record.environment.PSObject.Properties){$settings[$property.Name]=[string]$property.Value}
            foreach($key in $overrides.Keys){$settings[$key]=$overrides[$key]}
            $launch=$config.PSObject.Copy()
            $launch.log_dir=Join-Path $config.log_dir ("restart-"+[guid]::NewGuid().ToString("N"))
            New-Item -ItemType Directory -Path $launch.log_dir -Force | Out-Null
            $new=Start-ManagedService $record $launch $settings
            $config.processes=@($config.processes | ForEach-Object {if($_.service -eq $record.service -and $_.instance -eq $record.instance){$new}else{$_}})
            Save-DeploymentConfig $config
            Wait-ManagedService $new 45 ([int]$config.server_threads)
            Save-DeploymentConfig $config
        }
    }
} finally {$guard.Dispose()}
