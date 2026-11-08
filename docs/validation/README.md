# Integration validation coverage

Run instructions and evidence locations are documented in [system-tests](../../system-tests/README.md). The suite uses Java 17, real HTTP services and isolated SQLite/datalake data.

| Area | Assertion |
| --- | --- |
| Search | Keyword ranking, AND membership against postings, repeated terms and consistent behavior across replicas |
| Filters | Author normalization, language, combined filters, empty year matches and empty results |
| Pagination | Stable index version, total, result order, complete coverage and no duplicates |
| Public API | Book details, missing books, filter facets, CORS and documented error responses |
| Internal API | Health, ingestion inventory/status, indexing status and Control validation/retry |
| Pipeline | Registration through ingestion and indexing to searchable results; terminal NOT_FOUND |
| Idempotency | Re-registration, repeated ingestion across replicas, repeated and concurrent index updates without duplicated postings |
| Dependencies | Total Ingestion outage and Indexing outage wait without accumulating attempts, then resume |
| Partial data | Missing completion markers, malformed markers and temporary files do not publish incomplete books |
| Restart | Search retains results; Control recovers active work after lease expiry |
| Atomic publication | Killing a rebuild during its SQLite write transaction preserves the previous complete index |

There are 16 contract/flow integration tests, 6 fault integration tests and 2 infrastructure tests. Loops exercise the configured replicas without inflating the JUnit test count. The normal service unit suites remain separate.

The checked-in suite does not claim load/performance certification, positive year matching on this yearless corpus, or exhaustive coverage of every possible upstream/network failure. Real Gutenberg availability remains an external prerequisite. The runner records actual outcomes in each run's `evidence/summary.json` and JUnit reports rather than committing generated evidence.