[CmdletBinding()]
param(
    [string]$DataRoot="",
    [string]$JavaExe="",
    [string]$MavenExe="",
    [ValidateRange(1,8)][int]$IngestionReplicas=2,
    [ValidateRange(1,8)][int]$SearchReplicas=3,
    [ValidateRange(0,50000)][int]$PortOffset=0,
    [string]$GutenbergBaseUrl="https://www.gutenberg.org",
    [switch]$SkipBuild
)
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
. (Join-Path $root "scripts\deployment-common.ps1")
if(-not $DataRoot){$DataRoot=Join-Path $root "data\system-validation"}
$DataRoot=Get-DeploymentDataDir $DataRoot
$data=Join-Path $DataRoot ([datetime]::UtcNow.ToString("yyyyMMddTHHmmss")+"-"+[guid]::NewGuid().ToString("N").Substring(0,8))
New-Item -ItemType Directory -Path (Join-Path $data "evidence") -Force | Out-Null
if(-not $MavenExe){$MavenExe=(Get-Command mvn.cmd -ErrorAction Stop).Source}
$java=Resolve-DeploymentJava $JavaExe
$saved=@{}
$names=@("JAVA_HOME","PATH","DATA_DIR","CONTROL_URL","INGESTION_URL","INDEXING_URL","SEARCH_URL","SYSTEM_PHASE","SYSTEM_FAULT_TESTS","SYSTEM_VALIDATION_TOKEN")
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,"Process")}
$owned=$false
$summary=[ordered]@{status="running";data_dir=$data;source_url=$GutenbergBaseUrl;started_at=[datetime]::UtcNow.ToString("o");finished_at=$null;error=$null;phases=@()}
function Invoke-ValidationMaven([string]$Pom,[string]$Goal,[string]$Phase,[string]$Groups="") {
    $log=Join-Path $data ("evidence\maven-"+$Phase+".log")
    $mavenArgs=@("-B","-f",$Pom)
    if($Groups){$mavenArgs+=@("-Psystem-tests","-Dgroups=$Groups",("-Dsystem.reports="+(Join-Path $data ("evidence\failsafe-"+$Phase))))}
    $mavenArgs+=$Goal
    $preference=$ErrorActionPreference
    try {
        $ErrorActionPreference="Continue"
        & $MavenExe @mavenArgs *> $log
        $code=$LASTEXITCODE
    } finally {$ErrorActionPreference=$preference}
    $script:summary.phases+=@{name=$Phase;exit_code=$code;log=$log}
    if($code -ne 0){Get-Content -LiteralPath $log -Tail 35;throw "Maven $Phase failed; inspect $log"}
}
try {
    $env:JAVA_HOME=Split-Path -Parent (Split-Path -Parent $java.Path)
    $env:PATH=(Join-Path $env:JAVA_HOME "bin")+";"+$saved.PATH
    if(-not $SkipBuild){Invoke-ValidationMaven (Join-Path $root "pom.xml") "package" "build"}
    $deployment=& (Join-Path $root "scripts\start-all.ps1") -DataDir $data -JavaExe $java.Path -IngestionReplicas $IngestionReplicas -SearchReplicas $SearchReplicas -ServerThreads 32 -ControlWorkers 4 -PortOffset $PortOffset -GutenbergBaseUrl $GutenbergBaseUrl
    $owned=$true
    $token=[guid]::NewGuid().ToString("N")
    @{token=$token;run_id=$deployment.run_id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $data "validation-owner.json") -Encoding UTF8
    $env:DATA_DIR=$data
    $env:CONTROL_URL=$deployment.endpoints.control
    $env:INGESTION_URL=@($deployment.endpoints.ingestion) -join ","
    $env:INDEXING_URL=$deployment.endpoints.indexing
    $env:SEARCH_URL=@($deployment.endpoints.search) -join ","
    $env:SYSTEM_VALIDATION_TOKEN=$token
    $env:SYSTEM_FAULT_TESTS="false"
    $env:SYSTEM_PHASE="contracts"
    Invoke-ValidationMaven (Join-Path $root "system-tests\pom.xml") "verify" "contracts" "contract,flow"
    $env:SYSTEM_FAULT_TESTS="true"
    $env:SYSTEM_PHASE="faults"
    Invoke-ValidationMaven (Join-Path $root "system-tests\pom.xml") "verify" "faults" "fault"
    $summary.status="passed"
} catch {
    $summary.status="failed";$summary.error=$_.Exception.Message
    throw
} finally {
    try {if($owned){& (Join-Path $root "scripts\stop-all.ps1") -DataDir $data}}
    catch {$summary.status="failed";$summary.error="Cleanup failed: "+$_.Exception.Message;Write-Error $summary.error -ErrorAction Continue}
    $summary.finished_at=[datetime]::UtcNow.ToString("o")
    $summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $data "evidence\summary.json") -Encoding UTF8
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],"Process")}
    Write-Host "Validation status: $($summary.status). Evidence: $data"
}
if($summary.status -ne "passed"){throw $summary.error}
