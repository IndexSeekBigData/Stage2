# Single-host deployment configuration

All services are independent Java 17 processes. The launcher supports Windows PowerShell 5.1 and PowerShell 7.
It runs from any working directory and records absolute paths. Build all modules from the repository root first:

```powershell
mvn clean package
```

## Processes and ports

| Service | Processes | Default port(s) | HTTP threads | Other threads | Shared storage |
| --- | --- | --- | --- | --- | --- |
| Control | 1 | 7000 | SERVER_MAX_THREADS | CONTROL_WORKERS=4 and one lease reaper | control/control.db, SQLite WAL |
| Ingestion | 1-8 | 7001, 7011, 7021, ... | SERVER_MAX_THREADS | HTTP client implementation | datalake, including .locks |
| Indexing | 1 | 7002 | SERVER_MAX_THREADS | Tokenizing in request threads; writes serialized | datamarts/datamarts.db, SQLite WAL |
| Search | 1-8 | 7003, 7013, 7023, ... | SERVER_MAX_THREADS | SQLite connection per request | Same datamart, read-only |

These ports can all be shifted by `-PortOffset` for an isolated deployment on the same PC.
The launcher intentionally supports one Control and one Indexing process. SQLite's single-writer limit
serializes transactions even across connections; adding Indexing processes would not remove that limit.
Control is the workflow's single point of progress: a restart retains its database and waits for live leases to expire.

`SERVER_MAX_THREADS=0` keeps Jetty's default. Read the actual `max_threads` in each health response;
the launcher saves it, instead of assuming a value. Positive settings start at 8, but a small pool may not
satisfy Jetty's connector thread budget on a given machine. The launcher reports startup failure and rolls back.
32 is used by the repeatable demonstration.

Clients, HTTP threads, Control workers and replicas are distinct:
a replica is a process; its HTTP requests use its own thread pool; Control workers make synchronous service calls;
the number of external clients is independent of all of those settings.

## Commands

Run from the repository root (replace the JDK path if necessary):

```powershell
./scripts/start-all.ps1 -DataDir C:/BD/inverseek/data -JavaExe C:/path/to/jdk-17/bin/java.exe
./scripts/stop-all.ps1 -DataDir C:/BD/inverseek/data
```

Replicated deployment:

```powershell
./scripts/start-all.ps1 -DataDir C:/BD/inverseek/data -IngestionReplicas 2 -SearchReplicas 3 -ControlWorkers 4 -ServerThreads 32 -JavaExe C:/path/to/jdk-17/bin/java.exe
./control-module/scripts/run-pipeline.ps1 -Ids 11,84,1342
./scripts/stop-all.ps1 -DataDir C:/BD/inverseek/data
```

No database/index deletion, automatic rebuild or book registration occurs in start-all.
Starting with an existing Control database resumes pending jobs. Use a new DataDir for an independent experiment.
The launcher never performs Git operations.

| Launcher option | Default | Meaning |
| --- | --- | --- |
| DataDir | Absolute repository data directory | Common storage and deployment registry root |
| IngestionReplicas | 1 | Ingestion processes, range 1-8 |
| SearchReplicas | 1 | Search processes, range 1-8 |
| ControlWorkers | 4 | Background workflow workers, range 1-64 |
| ServerThreads | 0 | Per-process Jetty maximum, actual value recorded |
| JavaExe | JAVA_HOME/bin/java.exe, otherwise java on PATH | Must report Java 17 |
| PortOffset | 0 | Shift every default port, range 0-50000 |
| StartupTimeoutSeconds | 60 | Readiness wait per process |
| GutenbergBaseUrl | https://www.gutenberg.org | Source endpoint; a local fixture source can be used for validation |
| CorsAllowedOrigins | * | Search CORS setting |

The launcher explicitly supplies application environment settings to each child, and restores its caller's environment.
Source URL, limits, timeouts, leases, retry settings, paths, ports and thread settings are recorded in run-config.json.
JAVA_TOOL_OPTIONS and other external JVM/OS settings are not controlled by this launcher and must be recorded
separately for performance experiments.

## Data and process ownership

Within DataDir:

```text
datalake/                     shared published books and .tmp
datalake/.locks/              persistent per-book OS lock files
datamarts/datamarts.db        Indexing writer, Search readers
control/control.db            workflow state
deployment.lock               exclusive registry command lock
pids.txt                      live managed process ids
run-config.json               latest run configuration and effective health values
logs/<run-id>/                stdout/stderr per replica and an archived run-config.json
```

Always pass the same DataDir to start and stop. All ports/artifacts are checked before processes are started.
If startup fails, already-created processes from that invocation are stopped. Existing services are never adopted or killed.
A second start refuses while a recorded deployment is live.

Stop verifies the PID, creation time, Java executable and exact JAR path against the registry before stopping a process.
A reused/unverifiable PID is refused and retained for inspection. Only managed processes are stopped.
This is protection against accidental PID reuse, not a security boundary against someone who can alter local files/processes.

On Windows Stop-Process terminates Java; it does not promise a graceful drain of in-flight HTTP calls.
Control stops before dependencies. Databases and books are retained; the next start recovers jobs according to lease expiry.
Do not clear pending leases merely because one Control process stopped.

## Replica routing and consistency

Control accepts a comma-separated `INGESTION_URL`. A thread-safe round-robin counter chooses one endpoint per call.
A failed call is handled through the existing retry policy; a later call rotates again. No extra immediate retries are hidden
inside the HTTP client, and connection refusal still returns the workflow attempt.
Health preserves the aggregate `dependencies.ingestion` value (UP, DEGRADED or DOWN) and adds
`ingestion[0]`, `ingestion[1]`, etc. Indexing keeps one URL.

Each Ingestion process uses the same local datalake. A per-book OS file lock covers the existence check,
download and complete publication. A duplicate retry on another replica waits and then rereads the marker.
The OS releases the lock when a process dies. Lock files remain on disk; never delete .locks while processes are running.
Different book ids can proceed concurrently. This supplements Control leases and the per-process striped lock.

Search replicas share no application state or JDBC connections. They read published WAL snapshots.
Use the endpoint array in run-config.json to rotate client requests; the demonstration does so.
No proxy is required for this deployment. nginx can be added later if a single front-end URL is needed.

Do not run a full `/index/rebuild` while Control is publishing books.
Wait for processing to finish and prevent new registrations before administrative reconstruction.
The launcher does not solve concurrent full-rebuild/publication scheduling.

All replicas must run on the same host with a local filesystem supporting file locks and atomic renames.
Do not place SQLite WAL databases on a network filesystem. This deployment is not a cluster.

## Repeatable comparison

```powershell
./scripts/compare-deployments.ps1 -JavaExe C:/path/to/jdk-17/bin/java.exe
```

The script reads twenty ids from demo-book-ids.txt. It creates two fresh data directories:
one Ingestion/one Search, then two Ingestion/three Search; Control and Indexing remain single instances.
It processes every book in both scenarios and verifies:
all twenty reach INDEXED, every Search replica serves each book, query/filter responses match,
all twenty completion markers are unique and their body hashes match, .tmp is empty,
and each Ingestion replica has downloaded books. Search scores/results are compared across scenarios;
publication version numbers are not treated as content identifiers across separate databases.

Default input is live Gutenberg, so the source can be temporarily unavailable or change between runs.
A failed book or content difference fails the comparison and retains logs.
For deterministic offline validation, supply `-GutenbergBaseUrl` pointing to a local Gutenberg-compatible fixture server.
The result explicitly records the source URL; fixture results must not be presented as live-download measurements.
This is a correctness/deployment demonstration, not a load benchmark.

The comparison uses fixed port defaults unless PortOffset is supplied and stops its own deployments in finally blocks.
Evidence is retained in the ignored data/deployment-validation directory by default.
