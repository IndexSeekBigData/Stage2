# InverSeek Ingestion Service

Downloads books from Project Gutenberg, splits them into header and body, and publishes them in the datalake. Independent Maven project using Java 17, Javalin 6.1.3 and Gson 2.11.

## Build and run

From `ingestion-service/`, with `JAVA_HOME` pointing to a JDK 17:

```powershell
mvn clean package
java -jar target\ingestion-service-1.0.0.jar
```

The service listens on port 7001. By default it stores data in `../data/datalake`, relative to the working directory, which is `data\datalake` at the repository root when launched from `ingestion-service/`. Git ignores that folder.

## Configuration

Defaults are in `src/main/resources/application.properties`. Environment variables override them. When launching from another directory, set the same absolute `DATALAKE_PATH` as Indexing.

The full contract is in `src/main/resources/openapi.yaml`.

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `ingestion.port` | `INGESTION_PORT` | `7001` | One port per replica |
| `datalake.path` | `DATALAKE_PATH` | `../data/datalake` | Keep it outside OneDrive |
| `datalake.partition-zone` | `DATALAKE_PARTITION_ZONE` | `UTC` | Time zone of the partition folders |
| `gutenberg.base-url` | `GUTENBERG_BASE_URL` | `https://www.gutenberg.org` | |
| `download.timeout-seconds` | `DOWNLOAD_TIMEOUT_SECONDS` | `60` | |
| `download.max-bytes` | `DOWNLOAD_MAX_BYTES` | `52428800` | |
| `server.max-threads` | `SERVER_MAX_THREADS` | `0` | 0 keeps the Javalin default (250); `/health` reports the value in use |

## Datalake layout

```
datalake/
  AAAAMMDD/HH/
    {id}.header.txt
    {id}.body.txt
    {id}.success.json   completion marker
  .tmp/                 writes in progress
  .locks/               persistent host-local per-book lock files
```

This is the Stage 1 layout plus the marker.

- A book exists for the rest of the system only when its marker exists.
- Files are written in `.tmp/` and moved into the partition with atomic renames. The marker is moved last.
- Paths in the marker are relative to the datalake root and use `/`.
- At startup, the service rebuilds its catalog from the markers and deletes leftovers older than 10 minutes. Younger ones may belong to a write in progress in another replica.

Marker from a real run:

```json
{
  "bookId": 1342,
  "headerPath": "20261104/13/1342.header.txt",
  "bodyPath": "20261104/13/1342.body.txt",
  "headerBytes": 899,
  "bodyBytes": 737914,
  "bodySha256": "ec5006ab5edb21cf0ee304712665ef03a8a96bc5ef2e8bb70877dca86973d79c",
  "ingestedAt": "2026-11-04T13:34:09.703906900Z"
}
```

## Endpoints

| Route | Consumer | Success | Errors |
| --- | --- | --- | --- |
| `POST /ingest/{book_id}` | Control Module | 200 with `status` `downloaded`, or `available` if it already existed | 400 `invalid_book_id`, 404 `book_not_found`, 422 `invalid_book_format`, 502 `upstream_error`, 504 `upstream_timeout`, 500 `storage_error` |
| `GET /ingest/status/{book_id}` | Control Module | 200 `available` | 404 `not_available`, 400 `invalid_book_id` |
| `GET /ingest/list` | Control Module, administration | 200 `{count, books}`, ascending | |
| `GET /health` | Operations | 200 with `status`, `books_available`, `downloads_since_start`, `max_threads` | |

`POST /ingest/{book_id}` is synchronous: a 200 means the book is published. It is also idempotent: repeating it does not download the book again and returns the same path.

Error bodies have the form `{"error", "message", "book_id"}`.

Caller guidance:

- 502 and 504 can be retried.
- 404 and 422 are final, so the Control Module should mark the book `NOT_FOUND`.
- Use a caller timeout of 90 s.

## Concurrency

- Jetty handles each request in a pool thread.
- A striped lock in `IngestionService` serializes requests for the same book inside one process.
- A per-book OS file lock in the shared datalake serializes the complete check/download/publication across processes.
- After acquiring the shared lock, a duplicate request rereads the marker and returns the already published book.
- This protects retries that overlap after a timeout or lease expiry; Control leases still coordinate workflow assignment.
- The OS releases locks when a process dies. Lock files remain on disk: never delete .locks while replicas run.
- Replicas must share the same local datalake; this is not a network-filesystem or multi-host protocol.

The root deployment scripts configure shared paths, replica ports and thread pools.
See [configuration matrix](../docs/deployment/configuration-matrix.md).

## Tests

- `mvn test` runs 29 tests, using a local fake Gutenberg server for the HTTP tests.
- `scripts\sample-ingestion.ps1` exercises a running service against the real Gutenberg.
- `http\ingestion.http` has runnable requests for IntelliJ or VS Code.
