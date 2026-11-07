# InverSeek endpoint inventory

Complete request, response and error schemas are in each service's OpenAPI file:

- [Ingestion](../../ingestion-service/src/main/resources/openapi.yaml)
- [Indexing](../../indexing-service/src/main/resources/openapi.yaml)
- [Search](../../search-service/src/main/resources/openapi.yaml)
- [Control Module](../../control-module/src/main/resources/openapi.yaml)

## Front-end-facing

| Provider | Consumer | Method | Route | Purpose | Completion | Idempotency |
| --- | --- | --- | --- | --- | --- | --- |
| Search (7003) | Front-end | GET | `/search` | Keyword search with author, language and year filters, pagination and ranking | Synchronous | Read-only |
| Search (7003) | Front-end | GET | `/books/{book_id}` | Metadata of one book | Synchronous | Read-only |
| Search (7003) | Front-end | GET | `/search/filters` | Available languages, authors and year range | Synchronous | Read-only |

## Internal

| Provider | Consumer | Method | Route | Purpose | Completion | Idempotency |
| --- | --- | --- | --- | --- | --- | --- |
| Ingestion (7001) | Control Module | POST | `/ingest/{book_id}` | Download a book and publish it in the datalake | Synchronous: 200 means published | Yes: no second download, same path |
| Ingestion (7001) | Control Module | GET | `/ingest/status/{book_id}` | Check whether a book is completely ingested | Synchronous | Read-only |
| Indexing (7002) | Control Module | POST | `/index/update/{book_id}` | Publish the metadata and postings of one book in one transaction | Synchronous: 200 means searchable | Yes: same postings; the index version increases |

## Operational and administrative

| Provider | Consumer | Method | Route | Purpose | Completion | Idempotency |
| --- | --- | --- | --- | --- | --- | --- |
| Ingestion (7001) | Administrator | GET | `/ingest/list` | List ingested books | Synchronous | Read-only |
| Indexing (7002) | Administrator | POST | `/index` | Reindex every ingested book, one publication per book | Synchronous | Yes |
| Indexing (7002) | Administrator | POST | `/index/rebuild` | Replace the whole index atomically | Synchronous | Yes |
| Indexing (7002) | Administrator | GET | `/index/status` | Index statistics and version | Synchronous | Read-only |
| Control Module (7000) | Administrator | POST | `/control/books` | Register books to process | Asynchronous: 202 means accepted; observe with `/control/books/{book_id}` | Yes: registered books keep their state |
| Control Module (7000) | Administrator | GET | `/control/books` | List books by state, with pagination | Synchronous | Read-only |
| Control Module (7000) | Administrator | GET | `/control/books/{book_id}` | State, attempts, last error and timestamps of one book | Synchronous | Read-only |
| Control Module (7000) | Administrator | POST | `/control/books/{book_id}/retry` | Process a finished book again | Asynchronous: 202 means accepted | Active books return 409 |
| Control Module (7000) | Administrator | GET | `/control/status` | Books per state, workers and active leases | Synchronous | Read-only |
| Control Module (7000) | Administrator, benchmarks | GET | `/control/metrics` | Coordination cost per phase | Synchronous | Read-only |
| Ingestion, Indexing, Search, Control Module | Operations; Control Module reads Ingestion and Indexing | GET | `/health` | Availability; Search returns 503 until an index exists; Control also reports its dependencies | Synchronous | Read-only |

## Non-HTTP interactions

Control Module owns `control.db`. Ingestion is the only writer of the datalake, and Indexing is the only writer of `datamarts.db`. Indexing reads the datalake, and Search reads `datamarts.db` in read-only mode. A book in the datalake exists only when its `{id}.success.json` marker exists.
