# InverSeek Indexing Service

Java 17 / Maven / Javalin, port 7002. Indexing is the only writer of the Stage 2 SQLite datamart.
The Python and benchmark implementations in the repository belong to the earlier stage and use separate storage.

## Build and run

From `indexing-service/`, with `JAVA_HOME` pointing to a JDK 17:

```powershell
mvn clean package
$env:DATALAKE_PATH = "C:/BD/stage2/data/datalake"
$env:DATAMARTS_PATH = "C:/BD/stage2/data/datamarts/datamarts.db"
java -jar target/indexing-service-1.0.0.jar
```

Give Ingestion the same absolute `DATALAKE_PATH`. No files are downloaded by Indexing.
Defaults are in `src/main/resources/application.properties`. Precedence: environment, legacy `-D` properties, properties file.

| Property | Environment | Default |
| --- | --- | --- |
| indexing.port | INDEXING_PORT | 7002 |
| datalake.path | DATALAKE_PATH | ../data/datalake |
| datamarts.path | DATAMARTS_PATH | ../data/datamarts/datamarts.db |
| server.max-threads | SERVER_MAX_THREADS | 0 (Jetty default) |

Relative paths resolve from the working directory. Both service defaults target the repository data directory when launched from their own module directories.
Use absolute paths when launching from IntelliJ or another directory. Positive thread limits must be at least 8 and sufficient for Jetty's connector/thread budget; 32 is a tested setting.

## Publication contract

Only published `{id}.success.json` markers are discovered; `.tmp` subtrees are excluded.
The marker must contain the Ingestion fields `bookId, headerPath, bodyPath, headerBytes, bodyBytes, bodySha256, ingestedAt`.
Paths must be relative, inside the datalake and match the marker's partition and id. Files must exist and have the declared sizes.
The selected body's SHA-256 is checked on the exact bytes used for indexing.

Duplicate ids across partitions are resolved by oldest ingestion instant, then header path for an exact tie. All indexing endpoints use this selection.
A malformed committed marker or missing published file aborts discovery, rather than publishing an incomplete rebuild.
Unpublished header/body files without a marker are ignored.

This reader cannot make concurrent replacement of a published Ingestion file safe. Until the Control Module provides cross-process ownership, run one Ingestion writer for any given book id.
Indexing mutations are serialized within a service instance; SQLite locks arbitrate separate processes. Replica coordination remains Step 06/07 work.

## SQLite contract for Search

Schema version: `PRAGMA user_version=1`.

```sql
books(
  book_id TEXT PRIMARY KEY, title TEXT, author TEXT, language TEXT, body_path TEXT,
  author_norm TEXT, year INTEGER, word_count INTEGER NOT NULL DEFAULT 0,
  content_sha TEXT, indexed_at TEXT
)
postings(
  term TEXT NOT NULL, book_id TEXT NOT NULL, tf INTEGER NOT NULL,
  PRIMARY KEY(term, book_id), FOREIGN KEY(book_id) REFERENCES books(book_id)
)
index_state(
  id INTEGER PRIMARY KEY CHECK(id=1),
  version INTEGER NOT NULL, books_indexed INTEGER NOT NULL, last_update TEXT
)
```

- `book_id` remains canonical decimal TEXT for compatibility with the existing datamart and API.
  Search must serialize it as a JSON number if required by its contract and break score ties with `CAST(book_id AS INTEGER) ASC`.
- The tokenizer is unchanged: lowercase with `Locale.ROOT`, ASCII letter words of at least three letters.
  Search must use exactly the same rule. `word_count` is the sum of indexed term frequencies, not a count of every word in the source.
- Missing metadata and `Unknown` become SQL NULL. `author_norm` is lowercase with combining accents removed.
- `language` is an ISO 639-1 code derived from an English language name, a known two-letter code or its ISO3 alias. Unrecognized or missing languages become NULL.
- `year` stays NULL: Gutenberg release dates are not original publication years.
- `content_sha` hashes the body bytes. `indexed_at` records indexing time in UTC.
- `index_state` always has row `id=1`. Empty new databases start at version 0.
- Each successful single-book publication, each book published by `POST /index`, and each rebuild/clear increments the version once.
  Re-indexing unchanged content also increments the version; hash-based skipping is not implemented.
- Book data, postings, count, timestamp and version commit together. A failed rebuild preserves the old index.
- WAL is enabled at initialization. Every service connection enables foreign keys and a 5000 ms busy timeout.
- `index_metadata` is retained as a compatibility mirror of `last_update`.

Search must open SQLite read-only, use `PRAGMA query_only=ON`, and read version/count/postings/results in one transaction.
Never share a JDBC connection across requests. A response is a consistent snapshot; different requests/pages may see different versions.
Do not hold long read transactions, which delay WAL checkpoint progress.

## Existing databases

On the next service startup, the legacy schema is migrated in a transaction without deleting books or postings:
`word/frequency` become `term/tf`; new metadata/state columns are added; languages/authors and token counts are normalized.
The legacy global timestamp is used for migrated `indexed_at` when available. The exact historical per-book indexing time is unavailable.
The old body hash cannot be reconstructed from SQL alone and remains NULL until reindexing.
A migrated nonempty index starts at version 1. Reopening an already migrated database does not publish a new version.

Back up a valuable database before first running the updated service. Old indexing binaries/SQL that depend on `word/frequency` cannot use the migrated schema.
No automatic rebuild is run at startup. Use the endpoints to reindex when ready.
Unknown newer schema versions are rejected.

## HTTP endpoints

See `src/main/resources/openapi.yaml`.

| Route | Result |
| --- | --- |
| POST /index | Reprocess selected completed books; reports total/indexed/failed. Each book is a separate publication. |
| POST /index/update/{book_id} | Publish one selected book; 404 if absent, 400 for invalid id |
| POST /index/rebuild | Prepare all selected books, then atomically replace the entire index |
| GET /index/status | Snapshot of count, timestamp, logical database size and index version |
| GET /health | Database-backed status, version, count and configured thread-pool maximum |

`/index` may return 200 with `failed > 0`; callers must inspect the counters.
A missing/unreadable datalake or invalid committed marker returns 500 `datalake_error`.
Database/unexpected failures return 500 `internal_error`.
Errors use `{"error":"code","message":"description"}`; unknown routes return JSON 404.
Book ids must be integers from 1 to 10000000; update responses retain a string id for backward compatibility.
`index_size_MB` is logical SQLite pages, excluding WAL and shared-memory file sizes.

## Tests

`mvn clean package` runs JUnit 5 unit and HTTP tests against temporary data only.
Coverage includes legacy migration, metadata normalization, foreign-key-safe updates, duplicate selection,
temporary-file exclusion, corrupt publication/hash rejection, rollback preserving the version,
read-only WAL snapshot consistency while another connection publishes, and API errors/status/thread limits.

Rebuild currently prepares all term-frequency maps in memory before writing; very large corpora need a later bounded-memory staging implementation.
The root aggregator currently includes only this module. Ingestion must still be built separately.
