package com.inverseek.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaRoutingTest {
    @TempDir Path root;

    private Config config(String ingestion, String indexing) {
        return new Config(0, root.resolve("control.db"), ingestion, indexing, 4, 3,
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(4),
                Duration.ofMillis(20), Duration.ofMillis(50), Duration.ofMillis(10), Duration.ofMillis(100), 0);
    }

    @Test void distributesConcurrentCallsAndPreservesSingleIndexingEndpoint() throws Exception {
        try (var first = new FakeServices(); var second = new FakeServices()) {
            Config config = config(first.ingestionUrl() + "/, " + second.ingestionUrl(), first.indexingUrl());
            assertEquals(List.of(first.ingestionUrl(), second.ingestionUrl()), config.ingestionUrls());
            var pipeline = new HttpPipeline(config);
            var pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Pipeline.Outcome>> calls = new ArrayList<>();
                for (int id = 1; id <= 20; id++) {
                    int book = id;
                    calls.add(() -> pipeline.ingest(book));
                }
                for (var result : pool.invokeAll(calls)) assertEquals(Pipeline.Kind.SUCCESS, result.get().kind());
            } finally {
                pool.shutdownNow();
            }
            assertEquals(10, first.ingestCalls.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum());
            assertEquals(10, second.ingestCalls.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum());
            for (int id = 1; id <= 20; id++) assertEquals(1, first.ingestCount(id) + second.ingestCount(id));
            assertEquals(Pipeline.Kind.SUCCESS, pipeline.index(1).kind());
            assertEquals(1, first.indexCount(1));
            assertEquals(0, second.indexCount(1));
        }
    }

    @Test void reportsEveryReplicaAndNextCallCanUseAnotherEndpointAfterRefusal() throws Exception {
        try (var live = new FakeServices()) {
            String offline;
            try (var stopped = new FakeServices()) {
                offline = stopped.ingestionUrl();
            }
            var pipeline = new HttpPipeline(config(offline + "," + live.ingestionUrl(), live.indexingUrl()));
            assertEquals(Pipeline.Kind.UNREACHABLE, pipeline.ingest(1).kind());
            assertEquals(Pipeline.Kind.SUCCESS, pipeline.ingest(1).kind());
            Map<String, String> health = pipeline.dependencies();
            assertEquals("DEGRADED", health.get("ingestion"));
            assertEquals("DOWN", health.get("ingestion[0]"));
            assertEquals("UP", health.get("ingestion[1]"));
            assertEquals("UP", health.get("indexing"));
        }
    }

    @Test void rejectsEmptyOrInvalidEndpointListsAndInvalidWaits() {
        for (String url : List.of("", "http://localhost:7001,", "file:///tmp", "localhost:7001",
                "http://localhost:7001?query=x")) {
            assertThrows(IllegalArgumentException.class, () -> config(url, "http://localhost:7002"));
        }
        for (var env : List.of(Map.of("CONTROL_REAPER_SECONDS", "-1"), Map.of("CONTROL_IDLE_MILLIS", "0"),
                Map.of("CONTROL_MAX_BACKOFF_SECONDS", "0"), Map.of("INGESTION_TIMEOUT_SECONDS", "0"))) {
            assertThrows(IllegalArgumentException.class, () -> Config.load(env));
        }
        assertEquals(List.of("http://localhost:7001"), Config.load(Map.of()).ingestionUrls());
    }
}
