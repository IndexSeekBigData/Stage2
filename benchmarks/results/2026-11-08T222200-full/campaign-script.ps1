<# Runs full measurements sequentially and retains logs, source patch and phase status. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$OutDir,
    [Parameter(Mandatory=$true)][string]$WorkRoot,
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [Parameter(Mandatory=$true)][string]$JavaExe,
    [Parameter(Mandatory=$true)][string]$PythonExe,
    [string]$PythonTools="",
    [ValidateSet("jmh","search-a","search-b","search-c","pipeline-d","pipeline-e","plots")][string]$StartAt="jmh"
)
. (Join-Path $PSScriptRoot "bench-common.ps1")
Use-Java $JavaExe
$OutDir=[IO.Path]::GetFullPath($OutDir)
$WorkRoot=[IO.Path]::GetFullPath($WorkRoot)
$Snapshot=[IO.Path]::GetFullPath($Snapshot)
$repo=$script:RepoDir
$shell=(Get-Process -Id $PID).Path
$manifestPath=Join-Path $OutDir "campaign.json"
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if(-not $manifest.PSObject.Properties["phases"]){$manifest | Add-Member -NotePropertyName phases -NotePropertyValue @()}
$manifest.status="running"
function Save-Campaign { $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding UTF8 }
function Run-Phase([string]$Name,[string]$Script,[string[]]$Parameters) {
    $log=Join-Path $OutDir ($Name+".log")
    $phase=[pscustomobject]@{name=$Name;status="running";started_at=[datetime]::UtcNow.ToString("o");finished_at=$null;exit_code=$null;log=$log;parameters=$Parameters}
    $manifest.phases+=@($phase)
    Save-Campaign
    Write-Host "Starting $Name; log: $log"
    $prior=$ErrorActionPreference
    try {
        $ErrorActionPreference="Continue"
        & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $Script) @Parameters *> $log
        $code=$LASTEXITCODE
    } finally {$ErrorActionPreference=$prior}
    $phase.exit_code=$code
    $phase.finished_at=[datetime]::UtcNow.ToString("o")
    $phase.status=if($code -eq 0){"passed"}else{"failed"}
    Save-Campaign
    if($code -ne 0){Get-Content -LiteralPath $log -Tail 25;throw "$Name failed; inspect $log"}
    Write-Host "Finished $Name"
}
$phases=@("jmh","search-a","search-b","search-c","pipeline-d","pipeline-e","plots")
$first=[array]::IndexOf($phases,$StartAt)
try {
    git -C $repo diff --binary | Set-Content -LiteralPath (Join-Path $OutDir "source.patch") -Encoding UTF8
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $OutDir "campaign-script.ps1") -Force
    if($first -le 0){Run-Phase "jmh" "run-jmh.ps1" @("-Datamart",(Join-Path $Snapshot "datamarts\datamarts.db"),"-OutDir",$OutDir,"-JavaExe",$JavaExe)}
    $search=@("-Snapshot",$Snapshot,"-DataDir",(Join-Path $WorkRoot "search"),"-OutDir",$OutDir,"-Queries",(Join-Path $repo "benchmarks\queries.txt"),"-JavaExe",$JavaExe)
    if($first -le 1){Run-Phase "search-a" "run-search.ps1" ($search+@("-Experiments","A"))}
    if($first -le 3){
        $a=@(Import-Csv -LiteralPath (Join-Path $OutDir "search.csv") | Where-Object experiment -eq "A")
        $averages=@($a | Group-Object concurrency | ForEach-Object {
            $mean=($_.Group | ForEach-Object {[double]::Parse($_.throughput_rps,[Globalization.CultureInfo]::InvariantCulture)} | Measure-Object -Average).Average
            [pscustomobject]@{clients=[int]$_.Name;throughput=$mean}
        })
        if($averages.Count -ne 5 -or $a.Count -ne 15){throw "Search A needs 15 complete runs before selecting saturation"}
        $max=($averages | Measure-Object throughput -Maximum).Maximum
        $selected=($averages | Where-Object throughput -ge ($max*0.95) | Sort-Object clients | Select-Object -First 1).clients
        $selection=@{clients=$selected;rule="smallest tested client count reaching 95 percent of maximum mean throughput in experiment A";averages=$averages}
        $selection | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutDir "saturation-selection.json") -Encoding UTF8
        if($first -le 2){Run-Phase "search-b" "run-search.ps1" ($search+@("-Experiments","B","-SaturationClients","$selected"))}
        if($first -le 3){Run-Phase "search-c" "run-search.ps1" ($search+@("-Experiments","C"))}
    }
    $pipeline=@("-Snapshot",$Snapshot,"-WorkRoot",(Join-Path $WorkRoot "pipeline"),"-OutDir",$OutDir,"-IngestIds",(Join-Path $repo "benchmarks\ingest-ids.txt"),"-JavaExe",$JavaExe)
    if($first -le 4){Run-Phase "pipeline-d" "run-pipeline-bench.ps1" ($pipeline+@("-Experiments","D"))}
    if($first -le 5){Run-Phase "pipeline-e" "run-pipeline-bench.ps1" ($pipeline+@("-Experiments","E","-IngestionReplicas","1,2"))}
    if($PythonTools){$env:PYTHONPATH=$PythonTools}
    $env:PYTHONDONTWRITEBYTECODE="1"
    & $PythonExe (Join-Path $repo "benchmarks\plots\plot.py") $OutDir *> (Join-Path $OutDir "plots.log")
    if($LASTEXITCODE -ne 0){throw "Plot generation failed; inspect plots.log"}
    $manifest.status="passed"
} catch {
    $manifest.status="failed"
    if(-not $manifest.PSObject.Properties["error"]){$manifest | Add-Member -NotePropertyName error -NotePropertyValue $_.Exception.Message}else{$manifest.error=$_.Exception.Message}
    throw
} finally {
    if(-not $manifest.PSObject.Properties["finished_at"]){$manifest | Add-Member -NotePropertyName finished_at -NotePropertyValue ([datetime]::UtcNow.ToString("o"))}else{$manifest.finished_at=[datetime]::UtcNow.ToString("o")}
    Save-Campaign
    Write-Host "Campaign $($manifest.status): $OutDir"
}