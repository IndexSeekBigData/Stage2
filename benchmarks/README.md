# Benchmarks (Stage 2)

Everything needed to measure the services on one machine: JMH microbenchmarks, a load client for the
Search API, scripts for the integration experiments, CPU/memory monitoring and the plots for the
report. Every number in the report must come from these scripts run on the same PC, with the
configuration saved next to the results.

| Path | Purpose |
| --- | --- |
| `corpus-ids.txt` | ~100 English Gutenberg books of varied sizes (the books are not committed) |
| `ingest-ids.txt` | 20 other books, downloaded again in every run of experiment E |
| `queries.txt` | 50 load-client queries: frequent and rare words, two words, filters |
| `prepare-corpus.ps1` | Downloads and indexes the corpus once and saves it as a snapshot |
| `run-jmh.ps1` | JMH microbenchmarks of the three services |
| `run-search.ps1` | Experiments A (clients), B (Search replicas), C (HTTP threads) |
| `run-pipeline-bench.ps1` | Experiments D (indexing), E (ingestion), data for F (coordination) |
| `monitor.ps1` | CPU and memory per process and for the host, every second |
| `load-client/` | Closed-loop HTTP load generator (Java 17, HdrHistogram) |
| `plots/plot.py` | Summary tables and plots from a results folder |
| `results/<date>/` | Results of one campaign (see below) |

## 1. Prepare the machine

- Java 17 (`java -version`) and Maven on the PATH, or pass `-JavaExe C:\path\to\jdk-17\bin\java.exe`.
- Python 3 with `pandas` and `matplotlib` for the plots.
- Plugged in, maximum performance power plan (`powercfg /getactivescheme` is saved automatically).
- Close the browser, IntelliJ, OneDrive, Discord, games... and do not use the PC while a script runs.
- Keep the data out of OneDrive, e.g. `C:\BD\bench\`. If Windows Defender slows the writes, add that
  folder to its exclusions and say so in the report.
- Every service JVM runs with `-Xms1G -Xmx4G` (set by the scripts through `JAVA_TOOL_OPTIONS`) and
  every JMH fork with the same options (`@Fork(jvmArgs = ...)`).

Build once from the repository root:

```powershell
mvn clean package
mvn -f benchmarks\load-client\pom.xml package
```

## 2. Corpus snapshot (once, needs Internet)

```powershell
.\benchmarks\prepare-corpus.ps1                    # C:\BD\bench\corpus -> C:\BD\bench\snapshot
```

The corpus goes through the normal pipeline (Control -> Ingestion -> Indexing). Books that Gutenberg
does not serve end as NOT_FOUND and are listed as warnings. Every later experiment except E starts
by mirroring this snapshot (`robocopy /MIR`) into its working folder, so all runs start from the same
state and the network does not affect indexing or search measurements.

## 3. JMH microbenchmarks (required)

```powershell
.\benchmarks\run-jmh.ps1 -Datamart C:\BD\bench\snapshot\datamarts\datamarts.db
```

About 30 to 40 minutes. Classes are in `src/test/java/com/inverseek/<service>/benchmark/` and use
5 warm-up and 10 measurement iterations of 1 s, one fork, `Throughput` and `AverageTime` modes.

| Service | Benchmark | Parameters |
| --- | --- | --- |
| Ingestion | `BookParserBenchmark.split`: header/body split | size SMALL, MEDIUM, LARGE; 1 thread |
| Ingestion | `DatalakeWriteBenchmark.store`: `.tmp` + SYNC writes + atomic moves + marker | sizes; 1 thread |
| Indexing | `TextProcessingBenchmark.tokenize` and `.prepareBook` (metadata + tokens + SHA-256) | sizes; -t 1, 2, 4, 8 |
| Indexing | `SqliteWriteBenchmark.saveBook`: one SQLite transaction per book | sizes; -t 1, 2, 4, 8 |
| Search | `SearchBenchmark.search`: 1 or 2 words, with or without filters | -t 1, 2, 4, 8 |

Books are generated deterministically (Zipf-distributed words, 150 KB / 750 KB / 3 MB), so the
microbenchmarks need no network and give the same input on every machine. `SearchBenchmark` uses a
copy of the corpus datamart given with `-Datamart`; without it, a synthetic index of 100 books.

Use `-Quick` (1 short iteration) only to check that everything runs: never report those numbers.
To run one benchmark by hand from a module folder:

```powershell
cd indexing-service
mvn test-compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=test" `
  "-Dexec.args=-cp %classpath org.openjdk.jmh.Main SqliteWriteBenchmark -t 4 -rf csv -rff target/jmh.csv"
```

`exec:exec` is required: with `exec:java` the JVMs forked by JMH do not get the test classpath and
fail with `ClassNotFoundException: org.openjdk.jmh.runner.ForkedMain`.

## 4. Integration experiments

One factor changes at a time; each configuration is repeated 3 times (`-Repetitions 5` if the
standard deviation is high) and the whole system is restarted between repetitions.

| Experiment | Factor | Fixed | Command |
| --- | --- | --- | --- |
| A. Load | clients 1, 4, 16, 64, 128 | 1 Search replica, default threads | `.\benchmarks\run-search.ps1 -Snapshot C:\BD\bench\snapshot -Experiments A` |
| B. Replicas | 1, 2, 3 Search replicas | clients = saturation point of A | `... -Experiments B -SaturationClients <from A>` |
| C. HTTP threads | `SERVER_MAX_THREADS` 8, 32, 250 | 1 replica, 128 clients | `... -Experiments C` |
| D. Indexing | `CONTROL_WORKERS` 1, 2, 4, 8 | corpus already downloaded | `.\benchmarks\run-pipeline-bench.ps1 -Snapshot C:\BD\bench\snapshot -Experiments D` |
| E. Ingestion | `CONTROL_WORKERS` 1, 2, 4, 8 (and `-IngestionReplicas 1,2`) | 20 new books per run | `.\benchmarks\run-pipeline-bench.ps1 -Experiments E` |
| F. Coordination | from D and E | | computed by `plot.py` |

- **Load client**: N threads, each sends a request, waits for the answer and sends the next one
  (N = concurrent clients). Requests alternate between the Search replicas and cycle through
  `queries.txt`. 10 s warm-up (not measured) and 60 s of measurement by default. It writes one row
  per run with throughput, mean/p50/p95/p99/max latency, errors (non-200 or timeout) and its own CPU.
  Check that the client CPU stays clearly below the Search CPU at high N, otherwise the client is
  the bottleneck. It can be run alone:
  `java -jar benchmarks\load-client\target\load-client-1.0.0.jar --targets http://localhost:7003 --concurrency 16 --queries benchmarks\queries.txt --out results.csv`
- **D** re-queues the INDEXED books of the snapshot with `POST /control/books/{id}/retry`; INDEXED
  books go back to DOWNLOADED, so only indexing is measured. SQLite allows one writer, so indexing is
  not expected to scale with workers.
- **E** starts from an empty folder and downloads from Project Gutenberg: the results depend on the
  network. Keep download time and indexing time apart in the report (plot F does).
- **F**: phase times per book come from the Control timestamps (`queued_at`, `download_started_at`,
  `downloaded_at`, `index_started_at`, `indexed_at`) of the books of each run, saved in `raw/`.
  `GET /control/metrics` is saved too, but it averages over every INDEXED book in the database, so in
  D it also counts the old download timestamps of the snapshot.
- Approximate duration: A 18 min, B and C 10 min each, D and E 20 to 40 min.

## 5. Plots and tables

```powershell
python benchmarks\plots\plot.py benchmarks\results\<date>
```

It reads only the results folder and writes into `results/<date>/plots/`:

| Output | Content |
| --- | --- |
| `jmh-summary.csv`, `jmh-time-per-op.png` | ms per operation (1 thread) with the JMH 99.9 % interval |
| `jmh-throughput-vs-threads.png` | parallel work (tokenize, search) vs the single SQLite writer |
| `search-summary.csv`, `search-A-clients.png` | throughput and p95 vs clients: where Search saturates |
| `search-B-replicas.png` | throughput vs replicas, with the CPU of all replicas and of the host |
| `search-C-threads.png` | throughput and p95 vs Jetty threads |
| `pipeline-summary.csv`, `pipeline-D-workers.png`, `pipeline-E-workers.png` | books/s vs workers against the ideal linear line |
| `pipeline-F-phases.png` | time per book: queue wait, download, wait between phases, indexing |

Values are the mean ± standard deviation of the repetitions, plus p50/p95/p99 for latency. CPU is a
share of the whole machine (100 % = all logical processors) averaged over the measurement window
only, per process and for the host (from `raw/*.monitor.csv`).

## Results folder

```text
results/<date>/
  host.txt                 CPU, cores, RAM, OS, power plan, Java version, Git commit, JVM options
  jmh/*.csv, jmh/*.txt     JMH results and logs
  search.csv               one row per load run (label = run name)
  pipeline.csv             one row per pipeline run
  failures.csv             runs that could not start or finish, with the error
  raw/                     monitor samples, run-config.json and Control data per run (not committed)
  plots/                   tables and figures used in the report
```

Commit the summary CSV files, `host.txt` and `plots/`; `raw/` stays out of Git (`.gitignore`).
