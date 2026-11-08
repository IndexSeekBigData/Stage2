# Benchmark campaign: 8 November 2026

Complete measurements on one Windows host; see [host.txt](host.txt) for hardware, Java and power plan.
The corpus contains 100 Project Gutenberg books; [corpus-manifest.json](corpus-manifest.json) records the content hashes.

- JMH: 14 CSVs, 116 parameter/mode combinations, 5 warmup and 10 measurement iterations per combination. No Quick runs.
- Search: 33 runs, 1,352,058 successful requests and zero HTTP errors. Each run used 5 s of warmup and 20 s of measurement.
- Pipeline: 12 reindexing runs of 100 books and 24 live download/indexing runs of 20 books. No failed or missing books.
- Three repetitions per system configuration. All 69 deployment records confirm shutdown.

## Search

Values are mean +/- sample standard deviation across the three repetitions. The p95 column summarizes each run's p95; it is not a pooled percentile.

| Experiment | Clients | Search replicas | HTTP pool | Successful requests/s | p95 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| A | 1 | 1 | default (250) | 259.58 +/- 1.42 | 4.90 +/- 0.03 |
| A | 4 | 1 | default (250) | 1256.02 +/- 5.68 | 12.12 +/- 0.26 |
| A | 16 | 1 | default (250) | 2397.75 +/- 13.08 | 12.65 +/- 0.08 |
| A | 64 | 1 | default (250) | 2255.67 +/- 74.14 | 42.76 +/- 1.40 |
| A | 128 | 1 | default (250) | 2230.00 +/- 49.30 | 73.17 +/- 2.71 |
| B | 16 | 1 | default (250) | 2384.23 +/- 13.16 | 12.73 +/- 0.04 |
| B | 16 | 2 | default (250) | 2484.15 +/- 59.25 | 12.28 +/- 0.31 |
| B | 16 | 3 | default (250) | 2371.18 +/- 68.19 | 14.30 +/- 0.35 |
| C | 128 | 1 | 16 | 2495.90 +/- 80.54 | 67.63 +/- 2.87 |
| C | 128 | 1 | 32 | 2247.58 +/- 25.17 | 264.66 +/- 18.12 |
| C | 128 | 1 | 250 | 2152.23 +/- 96.84 | 75.48 +/- 3.66 |

## Pipeline

| Experiment | Control workers | Ingestion replicas | Downloads/s | Indexed books/s |
| --- | ---: | ---: | ---: | ---: |
| D | 1 | 1 | — | 2.34 +/- 0.43 |
| D | 2 | 1 | — | 2.80 +/- 0.20 |
| D | 4 | 1 | — | 2.98 +/- 0.03 |
| D | 8 | 1 | — | 2.84 +/- 0.20 |
| E | 1 | 1 | 1.81 +/- 0.16 | 1.78 +/- 0.16 |
| E | 2 | 1 | 3.41 +/- 0.10 | 3.30 +/- 0.10 |
| E | 4 | 1 | 5.57 +/- 0.30 | 5.09 +/- 0.25 |
| E | 8 | 1 | 6.52 +/- 0.75 | 5.77 +/- 0.51 |
| E | 1 | 2 | 1.23 +/- 0.03 | 1.21 +/- 0.03 |
| E | 2 | 2 | 2.86 +/- 0.78 | 2.79 +/- 0.74 |
| E | 4 | 2 | 5.18 +/- 0.75 | 4.72 +/- 0.63 |
| E | 8 | 2 | 6.40 +/- 0.86 | 5.58 +/- 0.58 |

D reuses the downloaded files. E includes network and Control scheduling. The download rate ends at the last download; the pipeline rate ends at the last indexing completion.

## Evidence

- [Campaign manifest](campaign.json), [source patch](source.patch) and [executed campaign script](campaign-script.ps1).
- [Search CSV](search.csv), [pipeline CSV](pipeline.csv) and JMH CSVs/logs in `jmh/`.
- [JMH summary](plots/jmh-summary.csv), [Search summary](plots/search-summary.csv), [pipeline summary](plots/pipeline-summary.csv) and ten PNG figures in `plots/`.
- [Raw evidence archive](raw-evidence.zip): per-process samples, per-book timestamps, metrics and stopped deployment records. Extract it into a copy of this campaign directory to regenerate the plots using `python benchmarks/plots/plot.py <campaign-directory>`.

The services and load client shared the same 8-core, 16-logical-processor host with 15.3 GiB of usable RAM. The short HTTP windows characterize this workload on this host; they are not a long-running capacity test or results for a 4-core/32 GB reference server. JMH iteration distributions use the rounded values printed in the logs.
