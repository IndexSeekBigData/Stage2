# InverSeek Control Module

Orchestrates the workflow: it assigns registered books to workers, calls Ingestion and then Indexing, retries failures and recovers after crashes. Java 17, Maven, Javalin 6.1.3, SQLite. Port 7000.

A book only reaches Indexing after Ingestion confirms it, and it is searchable once Indexing has committed it. Search has no cache to refresh, so no further call is needed.

## Build and run

From `control-module/`, with a JDK 17 and with Ingestion and Indexing running:

```powershell
mvn clean package
java -jar target/control-module-1.0.0.jar
```

Then, with Search also running, register books and wait for them:

```powershell
./scripts/run-pipeline.ps1 -Ids 11,84,1342,9999999
```

## Configuration

Precedence: environment variables, then `application.properties`.

| Property | Environment variable | Default |
| --- | --- | --- |
| control.port | CONTROL_PORT | 7000 |
| control.db.path | CONTROL_DB_PATH | ../data/control/control.db |
| ingestion.url | INGESTION_URL | http://localhost:7001 |
| indexing.url | INDEXING_URL | http://localhost:7002 |
| control.workers | CONTROL_WORKERS | 4 |
| control.max-attempts | CONTROL_MAX_ATTEMPTS | 3 |
| ingestion.timeout-seconds | INGESTION_TIMEOUT_SECONDS | 90 |
| indexing.timeout-seconds | INDEXING_TIMEOUT_SECONDS | 120 |
| control.lease.ingest-seconds | CONTROL_LEASE_INGEST_SECONDS | 120 |
| control.lease.index-seconds | CONTROL_LEASE_INDEX_SECONDS | 180 |
| control.retry-delay-seconds | CONTROL_RETRY_DELAY_SECONDS | 2 |
| control.reaper-seconds | CONTROL_REAPER_SECONDS | 10 |
| control.idle-millis | CONTROL_IDLE_MILLIS | 500 |
| control.max-backoff-seconds | CONTROL_MAX_BACKOFF_SECONDS | 10 |
| server.max-threads | SERVER_MAX_THREADS | 0 (Jetty default) |

Each lease must exceed its call timeout; the service refuses to start otherwise. Relative paths resolve from the working directory. Only Control opens `control.db`.

## States

| State | Meaning | Final |
| --- | --- | --- |
| PENDING | Waiting for a download | No |
| DOWNLOADING | A worker holds the lease and calls Ingestion | No |
| DOWNLOADED | Published in the datalake, waiting for indexing | No |
| INDEXING | A worker holds the lease and calls Indexing | No |
| INDEXED | Published in the datamart and searchable | Yes |
| NOT_FOUND | Gutenberg has no usable book | Yes |
| FAILED | Attempts exhausted, or the request was rejected | Yes |

## Transitions

| From | Result | To |
| --- | --- | --- |
| PENDING | Worker claims it (attempts + 1) | DOWNLOADING |
| DOWNLOADING | Ingestion 200 | DOWNLOADED (attempts reset) |
| DOWNLOADING | Ingestion 404 or 422 | NOT_FOUND |
| DOWNLOADING | Ingestion 400 | FAILED |
| DOWNLOADING | Other status or timeout | PENDING after a delay, or FAILED when attempts are exhausted |
| DOWNLOADING | Ingestion unreachable | PENDING, without consuming the attempt |
| DOWNLOADED | Worker claims it (attempts + 1) | INDEXING |
| INDEXING | Indexing 200 | INDEXED |
| INDEXING | Indexing 400 | FAILED |
| INDEXING | Other status, including 404, or timeout | DOWNLOADED after a delay, or FAILED when attempts are exhausted |
| INDEXING | Indexing unreachable | DOWNLOADED, without consuming the attempt |
| DOWNLOADING or INDEXING | Lease expired | PENDING or DOWNLOADED, or FAILED when attempts are exhausted |
| FAILED or NOT_FOUND | `POST /control/books/{id}/retry` | PENDING |
| INDEXED | `POST /control/books/{id}/retry` | DOWNLOADED (reindex without downloading) |

**Attempts are counted per phase**, and the default maximum is 3. The delay before a retry starts at `control.retry-delay-seconds` and doubles per attempt.

**An unreachable service does not consume attempts.** Otherwise a short outage would mark every waiting book as FAILED, although nothing is wrong with them. Workers wait with exponential backoff up to `control.max-backoff-seconds`, and the books stay active with the reason in `last_error` until the service returns. Timeouts and error responses do consume attempts.

## Concurrency and leases

- **Claiming:** one atomic `UPDATE` moves the oldest waiting book to its working state and writes the lease. Two threads or two Control replicas cannot claim the same book. Downloaded books go first, so work in progress finishes before new work starts.
- **Completing:** every transition requires `book_id`, `state` and `lease_owner` to match. The owner is unique per process and thread.
- **A lease that expires while the work continues:** the reaper returns the book to waiting and another worker may repeat the work. When the first worker finishes, its update matches 0 rows, so it logs the loss and discards its result. Repeating the work is safe because Ingestion and Indexing are idempotent.
- **Crash recovery:** at startup and every `control.reaper-seconds`, expired leases are released. Leases that have not expired are kept, because they may belong to another live replica, so after a crash a book can wait up to its lease time (120 or 180 s by default).
- **Threads:** workers do not share in-memory state; all coordination goes through SQLite in WAL mode with a 30 s busy timeout.

## Endpoints

The complete schemas are in `src/main/resources/openapi.yaml`. Runnable requests are in `http/control.http`.

| Route | Result |
| --- | --- |
| POST /control/books | Body `{"ids":[...]}` or `{"from":a,"to":b}`, at most 10000 books. 202 with `inserted`, `ignored` and `total`. Idempotent. |
| GET /control/books | Filter with `state`, paginate with `offset` and `limit` (default 50, maximum 500) |
| GET /control/books/{id} | State, attempts, last error and timestamps; 404 if not registered |
| POST /control/books/{id}/retry | 202; 409 `not_retryable` for active books |
| GET /control/status | Books per state, `active`, workers and active leases |
| GET /control/metrics | Coordination cost per phase |
| GET /health | Availability and reachability of Ingestion and Indexing |

Errors have the form `{"error":"code","message":"description"}`.

## Metrics

`/control/metrics` gives the average and 95th percentile in milliseconds over INDEXED books, using the timestamps of each book's latest attempt:

| Field | From | To |
| --- | --- | --- |
| queue_wait_ms | queued_at | download_started_at |
| ingest_ms | download_started_at | downloaded_at |
| index_wait_ms | downloaded_at | index_started_at |
| index_ms | index_started_at | indexed_at |
| end_to_end_ms | queued_at | indexed_at |

`books_per_second` is the INDEXED books divided by the time from the first registration to the last indexing.

## Tests

`mvn clean package` runs JUnit 5 tests against fake Ingestion and Indexing HTTP servers. They cover the happy path, NOT_FOUND, retries, FAILED after the maximum attempts, 8 workers with 50 books processed exactly once, leases expiring during a hung call, stale workers, restart recovery, idempotent registration, and all endpoints and error codes.
