# Shared helpers for the Windows process launcher. Compatible with PowerShell 5.1 and 7.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-DeploymentDataDir([string]$Path) {
    if (-not [IO.Path]::IsPathRooted($Path)) { throw "DataDir must be an absolute path." }
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -eq [IO.Path]::GetPathRoot($full)) { return $full }
    return $full.TrimEnd('\', '/')
}

function Enter-DeploymentLock([string]$DataDir) {
    New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
    try {
        return [IO.File]::Open((Join-Path $DataDir "deployment.lock"),
            [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch {
        throw "Another deployment command is using $DataDir."
    }
}

function Read-DeploymentConfig([string]$DataDir) {
    $path = Join-Path $DataDir "run-config.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    $config = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($config.schema_version -ne 1 -or $config.data_dir -ne $DataDir) {
        throw "Unrecognized deployment registry: $path"
    }
    return $config
}

function Save-DeploymentConfig([object]$Config) {
    $json = $Config | ConvertTo-Json -Depth 20
    $directory = [string]$Config.data_dir
    $temporary = Join-Path $directory ("run-config." + [guid]::NewGuid().ToString("N") + ".tmp")
    try {
        $json | Set-Content -LiteralPath $temporary -Encoding UTF8
        Move-Item -LiteralPath $temporary -Destination (Join-Path $directory "run-config.json") -Force
        $json | Set-Content -LiteralPath (Join-Path $Config.log_dir "run-config.json") -Encoding UTF8
        $ids = @($Config.processes | Where-Object { $_.status -eq "running" } | ForEach-Object { $_.pid })
        [IO.File]::WriteAllLines((Join-Path $directory "pids.txt"), [string[]]$ids)
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}

function Get-ManagedProcess([object]$Record, [object]$Config) {
    if ($Record.service -notin @("control", "ingestion", "indexing", "search")) { throw "Invalid service in registry." }
    $expectedJar = Join-Path $Config.repository ($Record.service + "-service\target\" + $Record.service + "-service-1.0.0.jar")
    if ($Record.service -eq "control") { $expectedJar = Join-Path $Config.repository "control-module\target\control-module-1.0.0.jar" }
    if ([IO.Path]::GetFullPath($Record.jar) -ne [IO.Path]::GetFullPath($expectedJar)) { throw "Unexpected JAR in registry." }
    $process = Get-Process -Id ([int]$Record.pid) -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    $actualStart = $process.StartTime.ToUniversalTime()
    $recordedStart = if ($Record.started_at -is [datetime]) { $Record.started_at.ToUniversalTime() } else { [datetime]::Parse($Record.started_at).ToUniversalTime() }
    if ([math]::Abs(($actualStart - $recordedStart).TotalMilliseconds) -gt 1) {
        throw "PID $($Record.pid) was reused. Refusing to manage that process."
    }
    $details = Get-CimInstance Win32_Process -Filter ("ProcessId=" + [int]$Record.pid)
    if ($null -eq $details -or $null -eq $details.ExecutablePath -or $null -eq $details.CommandLine) {
        throw "Cannot verify the identity of PID $($Record.pid)."
    }
    if ([IO.Path]::GetFullPath($details.ExecutablePath) -ne [IO.Path]::GetFullPath($Config.java_executable)) {
        throw "PID $($Record.pid) is not the recorded Java executable."
    }
    $pattern = '(?i)(?:^|\s)"?' + [regex]::Escape([string]$Record.jar) + '"?(?:\s|$)'
    if ($details.CommandLine -notmatch $pattern) { throw "PID $($Record.pid) has a different command line." }
    return $process
}

function Resolve-DeploymentJava([string]$JavaExe) {
    if ([string]::IsNullOrWhiteSpace($JavaExe)) {
        if ($env:JAVA_HOME) { $JavaExe = Join-Path $env:JAVA_HOME "bin\java.exe" }
        else { $JavaExe = (Get-Command java -ErrorAction Stop).Source }
    }
    $path = (Get-Item -LiteralPath $JavaExe -ErrorAction Stop).FullName
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $path
    $info.Arguments = "-version"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $probe = [Diagnostics.Process]::Start($info)
    try {
        $version = $probe.StandardError.ReadToEnd() + $probe.StandardOutput.ReadToEnd()
        $probe.WaitForExit()
        if ($probe.ExitCode -ne 0 -or $version -notmatch 'version\s+"17(?:\.|")') {
            throw "A JDK 17 is required. Set JAVA_HOME or pass -JavaExe. Detected: $version"
        }
        return [pscustomobject]@{ Path = $path; Version = $version.Trim() }
    } finally { $probe.Dispose() }
}

function Assert-DeploymentPort([int]$Port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, $Port)
    try { $listener.Start() }
    catch { throw "Port $Port is in use. No existing service will be stopped." }
    finally { $listener.Stop() }
}

function Start-ManagedService([object]$Spec, [object]$Config, [hashtable]$Environment) {
    $module = $Spec.service + "-service"
    if ($Spec.service -eq "control") { $module = "control-module" }
    $directory = Join-Path $Config.repository $module
    $jar = Join-Path $directory ("target\" + $module + "-1.0.0.jar")
    $prefix = Join-Path $Config.log_dir ($Spec.service + "-" + $Spec.instance)
    $saved = @{}
    try {
        foreach ($key in $Environment.Keys) {
            $saved[$key] = [Environment]::GetEnvironmentVariable($key, "Process")
            [Environment]::SetEnvironmentVariable($key, [string]$Environment[$key], "Process")
        }
        $process = Start-Process -FilePath $Config.java_executable -ArgumentList @("-jar", ('"' + $jar + '"')) -WorkingDirectory $directory -WindowStyle Hidden -PassThru -RedirectStandardOutput "$prefix.stdout.log" -RedirectStandardError "$prefix.stderr.log"
        try {
            return [pscustomobject]@{
                service = $Spec.service; instance = $Spec.instance; port = $Spec.port
                url = ("http://localhost:" + $Spec.port); pid = $process.Id
                started_at = $process.StartTime.ToUniversalTime().ToString("o")
                jar = $jar; stdout = "$prefix.stdout.log"; stderr = "$prefix.stderr.log"
                environment = $Environment; status = "running"; health = $null
            }
        } catch {
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
            throw
        }
    } finally {
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], "Process") }
    }
}

function Wait-ManagedService([object]$Record, [int]$TimeoutSeconds, [int]$ServerThreads) {
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline) {
        $process = Get-Process -Id ([int]$Record.pid) -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            $tail = Get-Content -LiteralPath $Record.stderr -Tail 12 -ErrorAction SilentlyContinue
            throw "$($Record.service) exited during startup: $($tail -join ' ')"
        }
        try {
            $health = Invoke-RestMethod -Uri ($Record.url + "/health") -TimeoutSec 5
            if ($health.service -eq $Record.service -and $health.status -eq "UP") {
                if ($ServerThreads -gt 0 -and $health.max_threads -ne $ServerThreads) {
                    throw "Thread configuration mismatch for $($Record.service)."
                }
                $Record.health = $health
                return
            }
        } catch {
            # The process may still be opening its database/HTTP listener.
        }
        Start-Sleep -Milliseconds 200
    }
    throw "Timed out waiting for $($Record.service) at $($Record.url). Inspect $($Record.stderr)"
}

function Get-DeploymentSha256([string]$Path) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace("-", "").ToLowerInvariant() }
    finally { $stream.Dispose(); $algorithm.Dispose() }
}
