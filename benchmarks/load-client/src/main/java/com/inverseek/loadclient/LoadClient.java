package com.inverseek.loadclient;

import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Closed-loop load generator for {@code GET /search}.
 *
 * <p>{@code --concurrency N} threads each send one request, wait for the answer and send the next,
 * so N is the number of concurrent clients. Requests alternate between the {@code --targets}
 * (round robin) and cycle through the queries file. Nothing is recorded during the warm-up. At the
 * end, one row is appended to the CSV given by {@code --out}: configuration, requests, throughput,
 * latency percentiles (HdrHistogram, microsecond precision), errors and the client's own CPU use,
 * which must stay well below the server's to show that the client is not the bottleneck.
 */
public final class LoadClient {

    static final String HEADER = "timestamp,experiment,label,repetition,targets,search_replicas,server_threads,"
            + "concurrency,warmup_s,duration_s,requests,errors,throughput_rps,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,"
            + "client_cpu_pct,measurement_started_at,measurement_finished_at";

    private LoadClient() {
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(Options.USAGE);
            System.exit(2);
            return;
        }
        List<String> queries = readQueries(options.queries);
        Result result = run(options, queries);
        appendCsv(options, result);
        System.out.printf(Locale.ROOT,
                "requests=%d errors=%d throughput=%.1f req/s mean=%.2f ms p50=%.2f p95=%.2f p99=%.2f max=%.2f client_cpu=%.1f%%%n",
                result.requests, result.errors, result.throughput(), result.meanMs(), result.percentileMs(50),
                result.percentileMs(95), result.percentileMs(99), result.maxMs(), result.clientCpuPct);
        if (result.requests == 0) System.exit(1);
    }

    /** Reads one query per line, as URL parameters ({@code q=whale&language=en}); # starts a comment. */
    static List<String> readQueries(Path file) throws IOException {
        List<String> queries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            queries.add(encode(trimmed));
        }
        if (queries.isEmpty()) throw new IllegalArgumentException("No queries in " + file);
        return queries;
    }

    /** Encodes each value of {@code key=value&key=value}, so the file can contain plain text. */
    static String encode(String line) {
        List<String> parts = new ArrayList<>();
        for (String pair : line.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Query lines must be key=value pairs: " + line);
            String key = pair.substring(0, equals).strip();
            String value = pair.substring(equals + 1).strip();
            parts.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }

    static Result run(Options options, List<String> queries) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        List<URI> uris = new ArrayList<>();
        for (int t = 0; t < options.targets.size(); t++) {
            for (String query : queries) uris.add(URI.create(options.targets.get(t) + "/search?" + query));
        }
        AtomicLong next = new AtomicLong();
        Instant wallStart = Instant.now();
        long start = System.nanoTime();
        long measureFrom = start + options.warmup.toNanos();
        long measureTo = measureFrom + options.duration.toNanos();
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < options.concurrency; i++) {
            Worker worker = new Worker(client, uris, options.targets.size(), queries.size(), next,
                    measureFrom, measureTo, options.timeout);
            workers.add(worker);
        }
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers.size(); i++) {
            Thread thread = new Thread(workers.get(i), "load-client-" + i);
            thread.setDaemon(true);
            threads.add(thread);
        }
        threads.forEach(Thread::start);

        // Client CPU during the measurement window only.
        sleepUntil(measureFrom);
        long cpuStart = processCpuNanos();
        sleepUntil(measureTo);
        long cpuEnd = processCpuNanos();
        for (Thread thread : threads) thread.join(options.timeout.toMillis() + 5_000);

        Histogram total = new Histogram(TimeUnit.MINUTES.toMicros(10), 3);
        long errors = 0;
        for (Worker worker : workers) {
            total.add(worker.histogram);
            errors += worker.errors;
        }
        double seconds = options.duration.toNanos() / 1e9;
        int cores = Runtime.getRuntime().availableProcessors();
        double cpu = cpuStart < 0 || cpuEnd < 0 ? Double.NaN
                : 100.0 * (cpuEnd - cpuStart) / (options.duration.toNanos() * (double) cores);
        return new Result(total, total.getTotalCount(), errors, seconds, cpu,
                wallStart.plus(options.warmup), wallStart.plus(options.warmup).plus(options.duration));
    }

    private static void sleepUntil(long nanoTime) throws InterruptedException {
        long remaining;
        while ((remaining = nanoTime - System.nanoTime()) > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)));
        }
    }

    private static long processCpuNanos() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean bean) return bean.getProcessCpuTime();
        return -1;
    }

    static void appendCsv(Options options, Result result) throws IOException {
        Path out = options.out;
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        boolean header = !Files.exists(out) || Files.size(out) == 0;
        List<String> row = List.of(
                Instant.now().toString(), csv(options.experiment), csv(options.label), Integer.toString(options.repetition),
                csv(String.join(" ", options.targets)), Integer.toString(options.searchReplicas),
                Integer.toString(options.serverThreads), Integer.toString(options.concurrency),
                format(options.warmup.toNanos() / 1e9), format(options.duration.toNanos() / 1e9),
                Long.toString(result.requests), Long.toString(result.errors),
                format(result.throughput()), format(result.meanMs()), format(result.percentileMs(50)),
                format(result.percentileMs(95)), format(result.percentileMs(99)), format(result.maxMs()),
                format(result.clientCpuPct), result.measurementStarted.toString(), result.measurementFinished.toString());
        String line = String.join(",", row) + System.lineSeparator();
        Files.writeString(out, (header ? HEADER + System.lineSeparator() : "") + line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"")) return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value);
    }

    /** One closed-loop client. Only touched by its own thread until it finishes. */
    static final class Worker implements Runnable {
        private final HttpClient client;
        private final List<URI> uris;
        private final int targets;
        private final int queries;
        private final AtomicLong next;
        private final long measureFrom;
        private final long measureTo;
        private final Duration timeout;
        final Histogram histogram = new Histogram(TimeUnit.MINUTES.toMicros(10), 3);
        long errors;

        Worker(HttpClient client, List<URI> uris, int targets, int queries, AtomicLong next,
               long measureFrom, long measureTo, Duration timeout) {
            this.client = client;
            this.uris = uris;
            this.targets = targets;
            this.queries = queries;
            this.next = next;
            this.measureFrom = measureFrom;
            this.measureTo = measureTo;
            this.timeout = timeout;
        }

        @Override
        public void run() {
            while (true) {
                long sent = System.nanoTime();
                if (sent >= measureTo) return;
                long n = next.getAndIncrement();
                int target = (int) (n % targets);
                int query = (int) ((n / targets) % queries);
                HttpRequest request = HttpRequest.newBuilder(uris.get(target * queries + query))
                        .timeout(timeout).GET().build();
                boolean ok;
                try {
                    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    ok = response.statusCode() == 200;
                } catch (IOException e) {
                    ok = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long received = System.nanoTime();
                // A request counts when it started inside the window; its latency may end after it.
                if (sent >= measureFrom) {
                    if (ok) histogram.recordValue(Math.max(1, (received - sent) / 1_000));
                    else errors++;
                }
            }
        }
    }

    record Result(Histogram histogram, long requests, long errors, double seconds, double clientCpuPct,
                  Instant measurementStarted, Instant measurementFinished) {
        double throughput() {
            return requests / seconds;
        }

        double meanMs() {
            return requests == 0 ? Double.NaN : histogram.getMean() / 1000.0;
        }

        double percentileMs(double percentile) {
            return requests == 0 ? Double.NaN : histogram.getValueAtPercentile(percentile) / 1000.0;
        }

        double maxMs() {
            return requests == 0 ? Double.NaN : histogram.getMaxValue() / 1000.0;
        }
    }

    static final class Options {
        static final String USAGE = """
                Usage: java -jar load-client-1.0.0.jar --targets http://localhost:7003[,http://localhost:7013]
                         --concurrency N --queries benchmarks/queries.txt --out results/search.csv
                         [--warmup 10s] [--duration 60s] [--timeout 30s] [--experiment A] [--label text]
                         [--repetition 1] [--search-replicas 1] [--server-threads 0]
                """;
        List<String> targets = List.of();
        int concurrency = 1;
        Duration warmup = Duration.ofSeconds(10);
        Duration duration = Duration.ofSeconds(60);
        Duration timeout = Duration.ofSeconds(30);
        Path queries;
        Path out;
        String experiment = "";
        String label = "";
        int repetition = 1;
        int searchReplicas = 1;
        int serverThreads = 0;

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                if (!args[i].startsWith("--") || i + 1 >= args.length)
                    throw new IllegalArgumentException("Expected --name value pairs, got: " + args[i]);
                values.put(args[i].substring(2), args[++i]);
            }
            Options o = new Options();
            for (var entry : values.entrySet()) {
                String v = entry.getValue();
                switch (entry.getKey()) {
                    case "targets" -> o.targets = List.of(v.split(",")).stream().map(String::strip)
                            .filter(s -> !s.isEmpty()).map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                            .collect(Collectors.toList());
                    case "concurrency" -> o.concurrency = positive(v, "concurrency");
                    case "warmup" -> o.warmup = duration(v, "warmup");
                    case "duration" -> o.duration = duration(v, "duration");
                    case "timeout" -> o.timeout = duration(v, "timeout");
                    case "queries" -> o.queries = Path.of(v);
                    case "out" -> o.out = Path.of(v);
                    case "experiment" -> o.experiment = v;
                    case "label" -> o.label = v;
                    case "repetition" -> o.repetition = positive(v, "repetition");
                    case "search-replicas" -> o.searchReplicas = positive(v, "search-replicas");
                    case "server-threads" -> o.serverThreads = nonNegative(v, "server-threads");
                    default -> throw new IllegalArgumentException("Unknown option --" + entry.getKey());
                }
            }
            if (o.targets.isEmpty()) throw new IllegalArgumentException("--targets is required");
            if (o.queries == null) throw new IllegalArgumentException("--queries is required");
            if (o.out == null) throw new IllegalArgumentException("--out is required");
            if (o.warmup.isNegative() || o.timeout.isNegative() || o.timeout.isZero())
                throw new IllegalArgumentException("Warmup must be non-negative and timeout must be positive");
            if (o.duration.isNegative() || o.duration.isZero()) throw new IllegalArgumentException("--duration must be positive");
            return o;
        }

        private static int positive(String value, String name) {
            int parsed = nonNegative(value, name);
            if (parsed == 0) throw new IllegalArgumentException("--" + name + " must be positive");
            return parsed;
        }

        private static int nonNegative(String value, String name) {
            try {
                int parsed = Integer.parseInt(value.strip());
                if (parsed < 0) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " must be a non-negative integer");
            }
        }

        /** Accepts 60, 60s, 2m or 500ms. */
        static Duration duration(String value, String name) {
            String v = value.strip().toLowerCase(Locale.ROOT);
            try {
                if (v.endsWith("ms")) return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
                if (v.endsWith("s")) return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
                if (v.endsWith("m")) return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
                return Duration.ofSeconds(Long.parseLong(v));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " must be a duration such as 60s");
            }
        }
    }
}
