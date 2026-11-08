# System validation

Java 17 integration tests against the real Ingestion, Indexing, Search and Control services. The module is opt-in: normal root Maven builds do not execute these tests.

## Run the complete suite

From the repository root, in Windows PowerShell with Java 17 and Maven available:

```powershell
.\system-tests\scripts\validate-all.ps1
```

If Java or Maven is not on PATH, supply `-JavaExe` (the full path to java.exe) and `-MavenExe` (the full path to mvn.cmd). Use `-PortOffset 1000` when the default service ports are occupied. `-IngestionReplicas` and `-SearchReplicas` default to 2 and 3. Each accepts 1 through 8.

The runner builds the service JARs and runs their unit tests, creates a new data directory, starts the services, executes `contract,flow` followed by `fault`, and stops the deployment in `finally`. `-SkipBuild` requires previously built, up-to-date service JARs. Startup failures are rolled back by the deployment launcher. Any failed phase or cleanup returns an error.

Internet access to Project Gutenberg is required. The initial corpus uses IDs 11, 84, 345, 1342 and 2701, plus missing ID 9999999. The pipeline test registers an additional book. No service is replaced with a mock.

## Test groups

| Group | Tests | Coverage |
| --- | ---: | --- |
| infrastructure | 2 | OpenAPI validator loading and bounded polling |
| contract,flow | 16 | HTTP response schemas, search, filters, pagination, details, CORS, errors, pipeline, metrics and idempotency |
| fault | 6 | Dependency outages, incomplete publications, process restarts, expired leases and interrupted atomic rebuild |

The two infrastructure tests run during each module verification. Integration tests run sequentially with one Failsafe fork. Contract/flow and fault phases use separate JVMs and report directories.

Response schemas are checked against each service's local OpenAPI definition. Invalid requests intentionally validate only the response. A deliberately unknown route has no OpenAPI operation; its JSON 404 response is checked explicitly. Search result membership and unchanged postings are compared with read-only SQLite queries. Search assertions run on every configured replica.

The corpus currently has no publication years, so year filtering and facets verify the empty/null case; these system tests do not establish positive matching for a populated year. Metrics checks validate availability and structure, not performance targets.

## Isolation and fault injection

All generated files stay under a unique `data/system-validation/<run>/` directory, or a unique child of `-DataRoot`. The runner uses the shared deployment scripts and preserves process identity checks. Fault actions require `SYSTEM_FAULT_TESTS=true`, a validation token and a matching deployment run ID. Do not run this suite against a development or production data directory: contract/flow tests also register and reindex books.

Each fault scenario starts a fresh Control database inside the run directory and reuses the downloaded corpus and published datamart. Shorter test-specific timeouts and leases make recovery observable. Dependency outage tests stop all replicas of the selected dependency.

The rebuild interruption test installs a temporary SQLite trigger in the isolated datamart to hold the real write transaction, kills Indexing, checks that the previous complete snapshot remains available, removes the trigger and verifies a successful rebuild. Another test temporarily holds the SQLite writer lock to observe active Control work before a restart. These are test-only database fault injections. They do not change service code.

`manage-service.ps1` operates only on the matching managed deployment and saves replacement process records. The test cleanup restores stopped services; the outer runner then stops the entire validation deployment.

## Evidence and diagnosis

The final console message prints the run directory. Inspect:

- `evidence/summary.json`: final status, phase exit codes and log paths.
- `evidence/maven-build.log`, `maven-contracts.log`, `maven-faults.log`: Maven output for executed phases.
- `evidence/failsafe-contracts/` and `evidence/failsafe-faults/`: JUnit XML and text reports.
- `evidence/http-contracts/` and `evidence/http-faults/`: observed HTTP status, headers and response bodies.
- `evidence/process-actions/`: restart action logs and test configuration overrides.
- `run-config.json` and service logs: deployment identity, saved environment and process history via restart log directories.

Generated data and build output are ignored by Git. Preserve a failed run directory for diagnosis; each new invocation creates a separate run.

## Maven entry points

```powershell
# Compile tests and run infrastructure checks; no services required.
mvn -f system-tests/pom.xml package

# Against an already running disposable managed deployment:
# DATA_DIR must identify that deployment; URLs default to its run-config.json.
mvn -f system-tests/pom.xml -Psystem-tests verify '-Dgroups=contract,flow'
```

The second command modifies the disposable corpus. Use the full runner for fault tests: it supplies the ownership token, phase environment, service lifecycle and cleanup. The root `system-tests` profile includes this module in the reactor; the module's profile enables Failsafe execution.