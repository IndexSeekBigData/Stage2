# InverSeek endpoint inventory

Complete request, response and error schemas are in each service's OpenAPI file:

- [Ingestion](../../ingestion-service/src/main/resources/openapi.yaml)
- [Indexing](../../indexing-service/src/main/resources/openapi.yaml)
- [Search](../../search-service/src/main/resources/openapi.yaml)

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
| Ingestion, Indexing, Search | Operations | GET | `/health` | Availability; Search returns 503 until an index exists | Synchronous | Read-only |
