"""Regression checks for benchmark measurement boundaries and phase rates."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pandas as pd
import plot


class MeasurementTests(unittest.TestCase):
    def test_jmh_accepts_decimal_comma_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "jmh").mkdir()
            out = root / "plots"
            out.mkdir()
            (root / "jmh/locale.csv").write_text(
                '"Benchmark","Mode","Threads","Samples","Score","Score Error (99,9%)","Unit","Param: size"\n'
                '"example.ExampleBenchmark.run","avgt",1,10,"1,500","0,200","ms/op",SMALL\n',
                encoding="utf-8")
            with patch.object(plot, "save"):
                plot.jmh(root, out)
            result = pd.read_csv(out / "jmh-summary.csv").iloc[0]
            self.assertEqual(1.5, result.score)
            self.assertEqual(0.2, result.error)

    def test_search_monitor_uses_measurement_window_before_request_drain(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "raw").mkdir()
            out = root / "plots"
            out.mkdir()
            pd.DataFrame([dict(timestamp="2026-01-01T00:00:30Z", measurement_started_at="2026-01-01T00:00:00Z",
                               measurement_finished_at="2026-01-01T00:00:10Z", duration_s=10, label="window",
                               experiment="A", search_replicas=1, server_threads=0, concurrency=1,
                               throughput_rps=10, p95_ms=1)]).to_csv(root / "search.csv", index=False)
            rows = []
            for second, cpu in [(5, 10), (25, 90)]:
                for scope, name in [("host", "host"), ("process", "search-0")]:
                    rows.append(dict(timestamp=f"2026-01-01T00:00:{second:02d}Z", scope=scope, name=name,
                                     cpu_pct=cpu, memory_mb=100))
            pd.DataFrame(rows).to_csv(root / "raw/window.monitor.csv", index=False)
            with patch.object(plot, "save"):
                plot.search(root, out)
            result = pd.read_csv(out / "search-runs.csv").iloc[0]
            self.assertEqual(10, result.host_cpu_pct)
            self.assertEqual(10, result.search_cpu_pct)

    def test_download_rate_is_distinct_from_completed_pipeline_rate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "raw").mkdir()
            out = root / "plots"
            out.mkdir()
            pd.DataFrame([dict(started_at="2026-01-01T00:00:00Z", finished_at="2026-01-01T00:00:10Z",
                               experiment="E", label="phases", control_workers=1, ingestion_replicas=1,
                               books_per_s=0.1, elapsed_s=10)]).to_csv(root / "pipeline.csv", index=False)
            book = dict(book_id=11, state="INDEXED", queued_at="2026-01-01T00:00:00Z",
                        download_started_at="2026-01-01T00:00:01Z", downloaded_at="2026-01-01T00:00:02Z",
                        index_started_at="2026-01-01T00:00:03Z", indexed_at="2026-01-01T00:00:10Z")
            (root / "raw/phases.books.json").write_text(json.dumps([book]), encoding="utf-8")
            with patch.object(plot, "save"):
                plot.pipeline(root, out)
            result = pd.read_csv(out / "pipeline-runs.csv").iloc[0]
            self.assertEqual(0.5, result.ingestion_books_per_s)
            self.assertEqual(0.1, result.books_per_s_exact)
            self.assertEqual(1000, result.avg_ingest_ms)
            self.assertEqual(7000, result.avg_index_ms)


if __name__ == "__main__":
    unittest.main()