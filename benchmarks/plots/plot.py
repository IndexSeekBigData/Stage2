"""Tables and plots for the Stage 2 benchmarks.

Reads only the results folder written by the benchmark scripts:
    jmh/*.csv                 run-jmh.ps1
    search.csv, raw/*.csv     run-search.ps1 (load client rows + monitor samples)
    pipeline.csv, raw/*.json  run-pipeline-bench.ps1 (per-book Control timestamps + monitor samples)
and writes summary CSV files and PNG plots into <results>/plots/.

Repetitions are aggregated as mean and standard deviation; latency keeps p50, p95 and p99.
CPU percentages are shares of the whole machine (100 % = every logical processor busy) averaged
over the measurement window only (the warm-up is excluded).

Usage: python benchmarks/plots/plot.py benchmarks/results/<date>
Requires: pandas, matplotlib
"""
import json
import re
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402


def read_csv(path):
    return pd.read_csv(path, encoding="utf-8-sig")


def mean_std(frame, keys, columns):
    grouped = frame.groupby(keys)[columns].agg(["mean", "std", "count"])
    grouped.columns = [f"{c}_{s}" for c, s in grouped.columns]
    return grouped.reset_index()


def save(fig, out, name):
    fig.tight_layout()
    fig.savefig(out / name, dpi=150)
    plt.close(fig)
    print("  plot", out / name)


# --------------------------------------------------------------------------- JMH

def jmh(results, out):
    files = sorted((results / "jmh").glob("*.csv"))
    if not files:
        return
    frames = []
    for file in files:
        frame = read_csv(file)
        # JMH follows the host locale, including decimal commas inside quoted CSV values.
        frame.columns = [c.replace("99,9%", "99.9%") for c in frame.columns]
        for column in ("Score", "Score Error (99.9%)"):
            frame[column] = frame[column].map(lambda value: float(str(value).replace(",", ".")))
        params = [c for c in frame.columns if c.startswith("Param: ")]
        frame["param"] = frame[params].bfill(axis=1).iloc[:, 0].astype(str) if params else ""
        frames.append(frame)
    data = pd.concat(frames, ignore_index=True)
    data["benchmark"] = data["Benchmark"].str.split(".").str[-2:].str.join(".")
    data = data.rename(columns={"Mode": "mode", "Threads": "threads", "Score": "score",
                                "Score Error (99.9%)": "error", "Unit": "unit", "Samples": "samples"})
    table = data[["benchmark", "param", "mode", "threads", "samples", "score", "error", "unit"]]
    table = table.sort_values(["benchmark", "param", "mode", "threads"])
    table.to_csv(out / "jmh-summary.csv", index=False)
    print("  table", out / "jmh-summary.csv")

    # Time per operation with one thread, with the 99.9 % interval reported by JMH.
    single = table[(table["mode"] == "avgt") & (table["threads"] == 1)].copy()
    if not single.empty:
        single["name"] = single["benchmark"] + " [" + single["param"] + "]"
        fig, ax = plt.subplots(figsize=(9, 0.4 * len(single) + 1.5))
        ax.barh(single["name"], single["score"], xerr=single["error"].fillna(0), color="#4C72B0", capsize=3)
        ax.set_xscale("log")
        ax.set_xlabel("ms per operation (log scale)")
        ax.set_title("JMH: time per operation, 1 thread (error bar = 99.9 % interval)")
        ax.invert_yaxis()
        save(fig, out, "jmh-time-per-op.png")

    # Throughput against benchmark threads: parallel CPU work scales, the SQLite writer does not.
    thrpt = table[table["mode"] == "thrpt"].copy()
    multi = thrpt.groupby("benchmark")["threads"].nunique()
    names = [b for b in multi.index if multi[b] > 1]
    if names:
        fig, axes = plt.subplots(1, len(names), figsize=(4.5 * len(names), 3.8), squeeze=False)
        for ax, name in zip(axes[0], names):
            subset = thrpt[thrpt["benchmark"] == name]
            for param, rows in subset.groupby("param"):
                rows = rows.sort_values("threads")
                ax.errorbar(rows["threads"], rows["score"] * 1000, yerr=rows["error"].fillna(0) * 1000,
                            marker="o", capsize=3, label=param)
            ax.set_xscale("log", base=2)
            ax.set_xticks(sorted(subset["threads"].unique()))
            ax.get_xaxis().set_major_formatter(matplotlib.ticker.ScalarFormatter())
            ax.set_xlabel("JMH threads (-t)")
            ax.set_ylabel("operations / s (all threads)")
            ax.set_title(name)
            ax.legend(fontsize=8)
        fig.suptitle("JMH: throughput vs threads")
        save(fig, out, "jmh-throughput-vs-threads.png")


def jmh_iterations(results, out):
    """Recover iteration values from the JMH logs (as printed, with their rounding)."""
    rows = []
    pattern = re.compile(r"^Iteration\s+(\d+):\s+([0-9.,]+).*?\s(ms/op|ops/ms)\s*$")
    for path in sorted((results / "jmh").glob("*.txt")):
        metadata = {}
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("# Threads: "):
                metadata["threads"] = int(line.split()[2])
                metadata["param"] = ""
            elif line.startswith("# Benchmark mode: "):
                metadata["mode"] = "avgt" if "Average time" in line else "thrpt"
            elif line.startswith("# Benchmark: "):
                metadata["benchmark"] = ".".join(line.split()[-1].split(".")[-2:])
            elif line.startswith("# Parameters: "):
                metadata["param"] = line.split(" = ", 1)[-1].rstrip(")")
            elif match := pattern.match(line):
                iteration, value, unit = match.groups()
                rows.append(dict(metadata, iteration=int(iteration), score=float(value.replace(",", ".")), unit=unit))
    if not rows:
        return
    data = pd.DataFrame(rows)
    data.to_csv(out / "jmh-iterations.csv", index=False)
    groups = data[(data["mode"] == "avgt") & (data["threads"] == 1)].groupby(["benchmark", "param"])
    if not groups.ngroups:
        return
    fig, ax = plt.subplots(figsize=(10, 0.45 * groups.ngroups + 1.8))
    labels = []
    for index, ((benchmark, param), group) in enumerate(groups):
        scores = group["score"]
        labels.append(f"{benchmark} [{param}]")
        ax.scatter(scores, np.full(len(scores), index), color="#4C72B0", alpha=0.55)
        ax.errorbar(scores.mean(), index, xerr=scores.std() if len(scores) > 1 else 0,
                    fmt="D", color="#C44E52", capsize=3)
    ax.set_yticks(range(len(labels)), labels)
    ax.invert_yaxis()
    ax.set_xscale("log")
    ax.set_xlabel("ms/op (log scale); dots: iterations; diamonds and bars: mean and sample SD")
    ax.set_title("JMH: measured iteration distribution at one thread")
    save(fig, out, "jmh-iteration-distribution.png")


# ----------------------------------------------------------------------- monitor

def monitor_window(results, label, start, end):
    """Average CPU and memory inside [start, end] from raw/<label>.monitor.csv."""
    path = results / "raw" / f"{label}.monitor.csv"
    if not path.exists():
        return {}
    samples = read_csv(path)
    samples["timestamp"] = pd.to_datetime(samples["timestamp"], utc=True, format="ISO8601")
    window = samples[(samples["timestamp"] >= start) & (samples["timestamp"] <= end)]
    if window.empty:
        return {}
    values = {}
    host = window[window["scope"] == "host"]
    values["host_cpu_pct"] = host["cpu_pct"].mean()
    values["host_memory_mb"] = host["memory_mb"].mean()
    processes = window[window["scope"] == "process"].copy()
    processes["service"] = processes["name"].str.rsplit("-", n=1).str[0]
    # Sum the replicas of a service at each instant, then average over the window.
    per_service = processes.groupby(["service", "timestamp"])[["cpu_pct", "memory_mb"]].sum().reset_index()
    for service, rows in per_service.groupby("service"):
        values[f"{service}_cpu_pct"] = rows["cpu_pct"].mean()
        values[f"{service}_memory_mb"] = rows["memory_mb"].mean()
    for name, rows in processes.groupby("name"):
        values[f"{name}_cpu_pct"] = rows["cpu_pct"].mean()
    return values


def search_monitor_series(results, out, runs):
    """Align repetitions to the start of measurement, then summarize CPU/RSS over time."""
    selection = results / "saturation-selection.json"
    if not selection.exists():
        return
    clients = json.loads(selection.read_text(encoding="utf-8-sig"))["clients"]
    selected = runs[(runs["experiment"] == "A") & (runs["concurrency"] == clients)]
    traces = []
    for row in selected.itertuples():
        path = results / "raw" / f"{row.label}.monitor.csv"
        if not path.exists():
            continue
        samples = read_csv(path)
        stamps = pd.to_datetime(samples["timestamp"], utc=True, format="ISO8601")
        samples["elapsed_s"] = (stamps - row.start).dt.total_seconds()
        grid = np.arange(0, (row.end - row.start).total_seconds() + 0.01)
        for name in ("host", "search-0", "control-0", "indexing-0", "ingestion-0"):
            series = samples[samples["name"] == name].sort_values("elapsed_s")
            if series.empty:
                continue
            interpolated = {metric: np.interp(grid, series["elapsed_s"], series[metric], left=np.nan, right=np.nan)
                            for metric in ("cpu_pct", "memory_mb")}
            traces.append(pd.DataFrame(dict(name=name, elapsed_s=grid, **interpolated)))
    if not traces:
        return
    summary = mean_std(pd.concat(traces), ["name", "elapsed_s"], ["cpu_pct", "memory_mb"])
    summary.to_csv(out / "search-monitor-series.csv", index=False)
    fig, axes = plt.subplots(3, 1, figsize=(8, 8), sharex=True)
    for name, series in summary.groupby("name"):
        for ax, metric in ((axes[0], "cpu_pct"), (axes[1] if name == "host" else axes[2], "memory_mb")):
            avg = series[f"{metric}_mean"]
            std = series[f"{metric}_std"].fillna(0)
            line, = ax.plot(series["elapsed_s"], avg, label=name)
            ax.fill_between(series["elapsed_s"], (avg - std).clip(lower=0), avg + std,
                            color=line.get_color(), alpha=0.15)
    axes[0].set_ylabel("CPU (% of host)")
    axes[1].set_ylabel("Host used memory (MiB)")
    axes[2].set_ylabel("Process working set (MiB)")
    axes[2].set_xlabel("Seconds since measurement started")
    for ax in axes:
        ax.legend(loc="best", fontsize=8)
    fig.suptitle(f"Search at {clients} clients: mean and sample SD across repetitions")
    save(fig, out, "search-cpu-memory-over-time.png")


# ------------------------------------------------------------------------ search

def search(results, out):
    path = results / "search.csv"
    if not path.exists():
        return
    runs = read_csv(path)
    if not {"measurement_started_at", "measurement_finished_at"}.issubset(runs.columns):
        raise ValueError("Search results need explicit measurement timestamps; rerun the updated load client")
    runs["start"] = pd.to_datetime(runs["measurement_started_at"], utc=True, format="ISO8601")
    runs["end"] = pd.to_datetime(runs["measurement_finished_at"], utc=True, format="ISO8601")
    extra = [monitor_window(results, row.label, row.start, row.end) for row in runs.itertuples()]
    runs = pd.concat([runs, pd.DataFrame(extra)], axis=1)
    runs.to_csv(out / "search-runs.csv", index=False)
    search_monitor_series(results, out, runs)

    metrics = ["throughput_rps", "mean_ms", "p50_ms", "p95_ms", "p99_ms", "max_ms", "errors", "client_cpu_pct",
               "host_cpu_pct", "search_cpu_pct", "search_memory_mb"]
    metrics = [m for m in metrics if m in runs.columns]
    summary = mean_std(runs, ["experiment", "search_replicas", "server_threads", "concurrency"], metrics)
    summary.to_csv(out / "search-summary.csv", index=False)
    print("  table", out / "search-summary.csv")

    a = summary[summary["experiment"] == "A"].sort_values("concurrency")
    if not a.empty:
        fig, ax = plt.subplots(figsize=(7, 4.2))
        ax.errorbar(a["concurrency"], a["throughput_rps_mean"], yerr=a["throughput_rps_std"].fillna(0),
                    marker="o", capsize=3, color="#4C72B0", label="requests/s")
        ax.set_xscale("log", base=2)
        ax.set_xticks(a["concurrency"])
        ax.get_xaxis().set_major_formatter(matplotlib.ticker.ScalarFormatter())
        ax.set_xlabel("concurrent clients (1 Search replica, default HTTP threads)")
        ax.set_ylabel("requests / s", color="#4C72B0")
        right = ax.twinx()
        right.errorbar(a["concurrency"], a["p95_ms_mean"], yerr=a["p95_ms_std"].fillna(0), marker="s",
                       capsize=3, color="#C44E52", label="p95 latency")
        right.set_ylabel("p95 latency (ms)", color="#C44E52")
        ax.set_title("A. Search load: throughput and p95 latency vs clients")
        save(fig, out, "search-A-clients.png")

    b = summary[summary["experiment"] == "B"].sort_values("search_replicas")
    if not b.empty:
        fig, ax = plt.subplots(figsize=(7, 4.2))
        x = np.arange(len(b))
        ax.bar(x, b["throughput_rps_mean"], yerr=b["throughput_rps_std"].fillna(0), capsize=3, color="#4C72B0")
        ax.set_xticks(x)
        ax.set_xticklabels([f"{int(r)} replica(s)" for r in b["search_replicas"]])
        ax.set_ylabel("requests / s")
        clients = int(b["concurrency"].iloc[0])
        ax.set_title(f"B. Search replicas on one host ({clients} clients)")
        if "search_cpu_pct_mean" in b:
            right = ax.twinx()
            right.plot(x, b["search_cpu_pct_mean"], "s-", color="#DD8452", label="CPU of all Search replicas")
            if "host_cpu_pct_mean" in b:
                right.plot(x, b["host_cpu_pct_mean"], "^--", color="#55A868", label="host CPU")
            right.set_ylabel("CPU % of the machine")
            right.set_ylim(0, 100)
            right.legend(loc="lower right", fontsize=8)
        save(fig, out, "search-B-replicas.png")

    c = summary[summary["experiment"] == "C"].sort_values("server_threads")
    if not c.empty:
        fig, ax = plt.subplots(figsize=(7, 4.2))
        labels = [str(int(t)) for t in c["server_threads"]]
        x = np.arange(len(c))
        ax.bar(x, c["throughput_rps_mean"], yerr=c["throughput_rps_std"].fillna(0), capsize=3, color="#4C72B0")
        ax.set_xticks(x)
        ax.set_xticklabels(labels)
        ax.set_xlabel("SERVER_MAX_THREADS (Jetty pool of the Search process)")
        ax.set_ylabel("requests / s", color="#4C72B0")
        right = ax.twinx()
        right.errorbar(x, c["p95_ms_mean"], yerr=c["p95_ms_std"].fillna(0), fmt="s-", capsize=3, color="#C44E52")
        right.set_ylabel("p95 latency (ms)", color="#C44E52")
        clients = int(c["concurrency"].iloc[0])
        ax.set_title(f"C. HTTP threads, 1 replica, {clients} clients")
        save(fig, out, "search-C-threads.png")


# ---------------------------------------------------------------------- pipeline

def to_time(value):
    """ISO timestamp (string from Windows PowerShell 5.1, or already a date in PowerShell 7) in UTC."""
    if value is None or value == "":
        return pd.NaT
    stamp = pd.Timestamp(value)
    return stamp.tz_convert("UTC") if stamp.tzinfo else stamp.tz_localize("UTC")


def phases(results, row):
    """Per-book phase durations (ms) of the books processed in one pipeline run."""
    path = results / "raw" / f"{row.label}.books.json"
    if not path.exists():
        return pd.DataFrame()
    books = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(books, dict):
        books = [books]
    started = to_time(row.started_at)
    rows = []
    for book in books:
        if book.get("state") != "INDEXED" or not book.get("indexed_at"):
            continue
        times = {k: to_time(book.get(k)) for k in
                 ("queued_at", "download_started_at", "downloaded_at", "index_started_at", "indexed_at")}
        if times["indexed_at"] < started:
            continue  # Not part of this run.

        def ms(first, second):
            if pd.isna(times[first]) or pd.isna(times[second]):
                return np.nan
            return (times[second] - times[first]).total_seconds() * 1000

        reindex = row.experiment == "D"
        rows.append({
            "book_id": book["book_id"],
            # D re-queues already downloaded books: there is no queue wait or download in that run.
            "queue_wait_ms": np.nan if reindex else ms("queued_at", "download_started_at"),
            "ingest_ms": np.nan if reindex else ms("download_started_at", "downloaded_at"),
            "index_wait_ms": ms("downloaded_at", "index_started_at"),
            "index_ms": ms("index_started_at", "indexed_at"),
            "end_to_end_ms": ms("downloaded_at", "indexed_at") if reindex else ms("queued_at", "indexed_at"),
            "indexed_at": times["indexed_at"],
            "downloaded_at": times["downloaded_at"],
        })
    return pd.DataFrame(rows)


def pipeline(results, out):
    path = results / "pipeline.csv"
    if not path.exists():
        return
    runs = read_csv(path)
    details = []
    for row in runs.itertuples():
        books = phases(results, row)
        values = {}
        if not books.empty:
            span = (books["indexed_at"].max() - to_time(row.started_at)).total_seconds()
            values["books_per_s_exact"] = len(books) / span if span > 0 else np.nan
            ingest_span = (books["downloaded_at"].max() - to_time(row.started_at)).total_seconds()
            values["ingestion_books_per_s"] = len(books) / ingest_span if row.experiment == "E" and ingest_span > 0 else np.nan
            for column in ("queue_wait_ms", "ingest_ms", "index_wait_ms", "index_ms", "end_to_end_ms"):
                values[f"avg_{column}"] = books[column].mean()
            values["p95_index_ms"] = books["index_ms"].quantile(0.95)
        values.update(monitor_window(results, row.label, to_time(row.started_at), to_time(row.finished_at)))
        details.append(values)
    runs = pd.concat([runs, pd.DataFrame(details)], axis=1)
    runs.to_csv(out / "pipeline-runs.csv", index=False)
    metrics = [c for c in runs.columns if c in ("books_per_s", "books_per_s_exact", "ingestion_books_per_s", "elapsed_s", "avg_queue_wait_ms",
                                                "avg_ingest_ms", "avg_index_wait_ms", "avg_index_ms",
                                                "avg_end_to_end_ms", "p95_index_ms", "host_cpu_pct",
                                                "indexing_cpu_pct", "control_cpu_pct", "ingestion_cpu_pct")]
    summary = mean_std(runs, ["experiment", "ingestion_replicas", "control_workers"], metrics)
    summary.to_csv(out / "pipeline-summary.csv", index=False)
    print("  table", out / "pipeline-summary.csv")

    for experiment, title in (("D", "D. Indexing throughput vs Control workers"),
                              ("E", "E. Completed downloads vs Control workers (includes scheduling)")):
        rate = "ingestion_books_per_s" if experiment == "E" else "books_per_s_exact"
        subset = summary[summary["experiment"] == experiment]
        if subset.empty:
            continue
        fig, ax = plt.subplots(figsize=(7, 4.2))
        for replicas, rows in subset.groupby("ingestion_replicas"):
            rows = rows.sort_values("control_workers")
            label = f"{int(replicas)} Ingestion replica(s)" if experiment == "E" else "measured"
            ax.errorbar(rows["control_workers"], rows[f"{rate}_mean"], yerr=rows[f"{rate}_std"].fillna(0),
                        marker="o", capsize=3, label=label)
            base = rows[f"{rate}_mean"].iloc[0] / rows["control_workers"].iloc[0]
            ax.plot(rows["control_workers"], base * rows["control_workers"], "k:", alpha=0.6,
                    label="ideal (linear)" if replicas == subset["ingestion_replicas"].min() else None)
        ax.set_xlabel("CONTROL_WORKERS")
        ax.set_ylabel("books / s")
        ax.set_xticks(sorted(subset["control_workers"].unique()))
        ax.set_title(title)
        ax.legend(fontsize=8)
        save(fig, out, f"pipeline-{experiment}-workers.png")

    # F. Where the time of one book goes, per configuration.
    parts = [("avg_queue_wait_ms", "queue wait"), ("avg_ingest_ms", "download + datalake"),
             ("avg_index_wait_ms", "wait between phases"), ("avg_index_ms", "indexing")]
    parts = [(c, n) for c, n in parts if f"{c}_mean" in summary]
    if parts:
        rows = summary.sort_values(["experiment", "ingestion_replicas", "control_workers"])
        labels = [f"{r.experiment} w={int(r.control_workers)}" + (f" i={int(r.ingestion_replicas)}" if r.experiment == "E" else "")
                  for r in rows.itertuples()]
        fig, ax = plt.subplots(figsize=(max(7, 0.8 * len(rows)), 4.5))
        bottom = np.zeros(len(rows))
        for column, name in parts:
            values = rows[f"{column}_mean"].fillna(0).to_numpy()
            ax.bar(labels, values, bottom=bottom, label=name)
            bottom += values
        ax.set_ylabel("average ms per book")
        ax.set_title("F. Coordination cost: time per book by phase")
        ax.legend(fontsize=8)
        plt.setp(ax.get_xticklabels(), rotation=30, ha="right")
        save(fig, out, "pipeline-F-phases.png")


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    results = Path(sys.argv[1]).resolve()
    out = results / "plots"
    out.mkdir(exist_ok=True)
    print("Results:", results)
    jmh(results, out)
    jmh_iterations(results, out)
    search(results, out)
    pipeline(results, out)


if __name__ == "__main__":
    main()
