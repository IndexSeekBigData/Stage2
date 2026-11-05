# InverSeek Search Service

Java 17, Maven, Javalin 6.1.3, SQLite schema version 1. Port 7003.
Search is the front-end-facing API; only Indexing writes the datamart.

## Build and run

From the repository root, with a JDK 17:

```powershell
$env:JAVA_HOME = "C:/path/to/jdk-17"
mvn clean package
```

The root reactor builds and tests Ingestion, Indexing and Search. Each module can also be built independently.
Run Search from `search-service/`:

```powershell
$env:DATAMARTS_PATH = "C:/Users/herna/IdeaProjects/Stage_2/data/datamarts/datamarts.db"
java -jar target/search-service-1.0.0.jar
```

The default database path is `../data/datamarts/datamarts.db`, resolved from the working directory.
When launching from the repository root or IntelliJ, explicitly set the same absolute `DATAMARTS_PATH` as Indexing.
Search can start before the database exists. Data endpoints and health return 503 until a compatible index appears, without a restart.

## Configuration

Precedence: environment variables > legacy Java `-D` system properties > `application.properties`.

| Property | Environment variable | Default |
| --- | --- | --- |
| search.port | SEARCH_PORT | 7003 |
| datamarts.path | DATAMARTS_PATH | ../data/datamarts/datamarts.db |
| search.default-limit | SEARCH_DEFAULT_LIMIT | 20 |
| search.max-limit | SEARCH_MAX_LIMIT | 100 |
| cors.allowed-origins | CORS_ALLOWED_ORIGINS | * |
| sqlite.busy-timeout-ms | SQLITE_BUSY_TIMEOUT_MS | 5000 |
| server.max-threads | SERVER_MAX_THREADS | 0 (Jetty default) |

Limits must satisfy 1 <= default <= maximum <= 100.
Positive thread counts must be at least 8 and sufficient for Jetty connector needs; 32 is covered by HTTP tests.
The health response reports the actual maximum in use.

CORS uses Javalin's bundled plugin, including OPTIONS preflight and error responses.
Use `*` for development or a comma-separated allowlist such as
`http://localhost:5173,https://books.example.com`. Origins have no path or trailing slash.
Credentials are disabled. Do not combine `*` with named origins.

## Endpoints

The complete request and response schemas are in `src/main/resources/openapi.yaml`.

| Endpoint | Response |
| --- | --- |
| GET /search?q=love | Keyword search with optional author, language, year, offset and limit |
| GET /books/1342 | Published metadata, including nullable values and indexing timestamp |
| GET /search/filters | Global available languages, top 100 authors and known year range |
| GET /health | Database-backed readiness, index version, corpus count and thread limit |

Example:

```json
{
  "query": "love",
  "filters": {"language": "en"},
  "total": 1,
  "offset": 0,
  "limit": 20,
  "index_version": 2,
  "results": [
    {
      "book_id": 1342,
      "title": "Pride and Prejudice",
      "author": "Jane Austen",
      "language": "en",
      "year": null,
      "score": 70.70101241711441
    }
  ]
}
```

The example's corpus/version/score describe one book with tf(love)=102; actual data may differ.
Identifiers are JSON numbers even though Indexing persists canonical decimal TEXT.
Search responses never expose `body_path` or `content_sha`.

## Query rules

- `q` is required, at most 4096 characters. It must yield 1-10 distinct indexed terms.
- The tokenizer is copied exactly from Indexing (only the package changes):
  lowercase with `Locale.ROOT`, ASCII letter words of at least three characters.
  Short words and duplicates are ignored. Multiple terms use AND; a missing term yields zero matches.
  Accented words follow that same ASCII rule; Search does not silently adopt a different Unicode tokenizer.
- `author`: literal partial match on lowercase, accent-stripped `author_norm`, maximum 100 characters after trimming.
  %, _ and ! are escaped and treated as literal characters. SQL values are always bound parameters.
- `language`: 2 or 3 ASCII letters, lowercased. Stored codes are ISO 639-1 (en/es/fr).
  A syntactically valid code without books returns 200 with zero matches; three-letter aliases are not converted.
- `year`: exact integer from 1 through 2100. NULL never matches a year.
  Current Gutenberg ingestion does not provide original publication years, so these may all be NULL.
- Empty/blank author, language and year are treated as absent.
- `offset`: integer 0 through 2147483647, default 0.
- `limit`: integer 1 through configured maximum, default 20 and maximum 100.
  Empty numeric pagination parameters are invalid.
- Known parameters must occur once. Unknown parameters are ignored.
- The response preserves the original query string; `filters` contains only active, normalized filters.
- Sorting is fixed: score descending, then numeric book_id ascending.

## Ranking and consistency

For each term t: `idf(t) = ln(1 + N / df(t))`.
Score is the sum of `tf(t,book) * idf(t)` for the distinct query terms.
N and df are calculated over the complete published corpus, before author/language/year filters.
Frequency is raw term frequency, without length normalization or stop-word removal.

Every request opens its own JDBC connection in read-only mode and enables `PRAGMA query_only=ON`.
There is no DDL, migration, indexing endpoint or writer dependency in Search.
Search validates schema version 1 and the required columns on every snapshot.
Indexing must enable WAL and publish books/postings/index_state in one transaction, as implemented by Indexing.

All queries for an endpoint execute inside one read transaction, including corpus state, document frequencies,
total count and the requested result page. An offset beyond total still returns the correct total.
Connections/transactions close after the response data has been assembled, including failure paths.

A single response always belongs to one index version. Different page requests can observe different versions.
Clients requiring a stable traversal should detect an `index_version` change and restart pagination.
Search does not retain historical snapshots or keep a global shared JDBC connection.

SQLite may access its WAL/shared-memory auxiliary files even with a read-only database connection.
Deploy on the same host with permissions to read the published database and its SQLite auxiliary files.
WAL coordination, local file paths and process configuration remain single-host assumptions; distributed deployment is outside this service.

## Status codes

- 200: successful query, including an empty result set.
- 400: `invalid_query`, `invalid_parameter` or `invalid_book_id`.
- 404: `book_not_found` or `route_not_found`.
- 503: `index_not_ready` for a missing file/state; `index_incompatible` for a different/incomplete schema;
  `index_unavailable` for database read, corruption or lock/storage errors.
- 500: unexpected `internal_error`.

Errors have `{"error":"code","message":"description"}`. Null metadata fields are explicitly serialized.
Health returns 503 when the index is unavailable rather than claiming readiness.

## Validation

```powershell
# Unit and HTTP tests against temporary databases only:
mvn clean package

# Repeatable checks against an already running service (expects book 1342 and term love):
./scripts/sample-search.ps1

# Or choose another already indexed book/query:
./scripts/sample-search.ps1 -BookId 11 -Query adventure -SecondTerm alice
```

The script calls only GET endpoints. JSON evidence is written under the repository's ignored
`data/search-validation/` directory by default; choose another destination with `-EvidencePath`.
The HTTP request examples in `http/search.http` can be run from IntelliJ or a REST client.

Tests cover known-score ranking, numeric tie order, AND/duplicate terms, all filters,
accent normalization, literal LIKE characters, empty and paginated results, top-100 facets,
missing/incompatible/uninitialized databases, unchanged database bytes, CORS and preflight,
and a concurrent writer publishing while read snapshots stay consistent.

Coordination, replicas, nginx, JMH and load benchmarking are outside the Search implementation.

## Verified implementation

Validated with Java 17.0.21: 30 Search tests and 92 tests across the complete Maven reactor, all passing.
Packaged-service integration was also checked with five synthetic Gutenberg-compatible HTTP fixtures
through independent Ingestion, Indexing and Search processes, including 30 searches during five rebuilds.
The Windows sample script passed against the real index; its database file hash was unchanged.
Local validation evidence is under the ignored data/search-validation/ directory.
