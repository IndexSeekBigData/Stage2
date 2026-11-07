# Deployment validation

Validation performed on 7 November 2026 with Temurin Java 17.0.21, Windows PowerShell 5.1 and PowerShell 7.
This is a correctness and process-lifecycle demonstration, not a performance benchmark.

## Automated service tests

The Maven reactor contains 135 passing JUnit tests: Ingestion 31, Indexing 38, Search 30 and Control 36.
New regression coverage includes concurrent round-robin routing, per-replica dependency health,
invalid URL/wait configuration, shared-datalake duplicate downloads and release of a lock after a failed source call.

## Real Gutenberg comparison

The committed demo-book-ids.txt contains the twenty book ids used in both fresh scenarios.

| Scenario | Control processes / workers | Ingestion processes | Indexing processes | Search processes | HTTP threads per process | Indexed books |
| --- | --- | --- | --- | --- | --- | --- |
| single | 1 / 4 | 1 | 1 | 1 | 32 | 20 |
| replicated | 1 / 4 | 2 | 1 | 3 | 32 | 20 |

Both scenarios downloaded from https://www.gutenberg.org; a fixture server was not used for this comparison.
The script verified identical query/filter results and scores, all twenty book details on every Search replica,
twenty unique completion markers, matching SHA-256 body hashes and no unfinished .tmp downloads.
Both Ingestion replicas received real downloads. Every owned process was stopped afterwards.

Raw run configuration, per-process logs and comparison.json remain outside Git in:
data/deployment-validation/live-gutenberg/20261107T181959-ca5ecfab/.

Reproduce with:

    ./scripts/compare-deployments.ps1 -JavaExe C:/path/to/jdk-17/bin/java.exe

Source availability or a corpus change can make a later run fail; failures are reported and evidence retained.

## Isolated lifecycle and failure checks

Using a separate local Gutenberg-compatible fixture source:
- Seven Java processes started: one Control, two Ingestion, one Indexing and three Search.
- A second start was refused without replacing or stopping the live deployment.
- Two Ingestion processes received the same id concurrently: one downloaded, the other returned available,
  with one source HTTP call and the same published path.
- A registry entry pointing at a different owned dummy process was refused; the dummy survived.
- Correcting that registry entry allowed normal cleanup.
- Stopping an already stopped deployment succeeded.
- An invalid Search CORS configuration caused startup rollback, with no managed processes left alive.
- Twenty fixture books also produced identical single/replicated results.

Passing evidence:
data/deployment-validation/checks-20261107T181750Z/validation.json.

PowerShell 7 separately checked port-collision refusal, caller environment restoration, four-process startup,
idempotent shutdown and actual default HTTP thread counts. Each service reported 250 for SERVER_MAX_THREADS=0.

Evidence:
data/deployment-validation/powershell7-ca8292b85d554131bbc1dcd55210b9d3/validation.json.

Existing application databases were not used by these demonstrations.
Detailed system fault validation and throughput/latency experiments remain separate work.
