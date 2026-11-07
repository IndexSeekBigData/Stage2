package com.inverseek.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Fake Ingestion and Indexing HTTP servers that count the calls per book. */
final class FakeServices implements AutoCloseable {
    record Reply(int status, String body, long delayMillis) {
        static Reply ok(String body) {
            return new Reply(200, body, 0);
        }
    }

    final Map<Integer, AtomicInteger> ingestCalls = new ConcurrentHashMap<>();
    final Map<Integer, AtomicInteger> indexCalls = new ConcurrentHashMap<>();
    volatile BiFunction<Integer, Integer, Reply> ingest = (id, call) ->
            Reply.ok("{\"book_id\":" + id + ",\"status\":\"downloaded\",\"path\":\"20261105/10/" + id + "\"}");
    volatile BiFunction<Integer, Integer, Reply> index = (id, call) ->
            Reply.ok("{\"book_id\":" + id + ",\"index\":\"updated\"}");

    private final HttpServer ingestion;
    private final HttpServer indexing;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    FakeServices() throws IOException {
        ingestion = server("/ingest/", ingestCalls, () -> ingest);
        indexing = server("/index/update/", indexCalls, () -> index);
    }

    private HttpServer server(String prefix, Map<Integer, AtomicInteger> calls,
                              java.util.function.Supplier<BiFunction<Integer, Integer, Reply>> behaviour) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(pool);
        server.createContext("/health", exchange -> reply(exchange, new Reply(200, "{\"status\":\"UP\"}", 0)));
        server.createContext(prefix, exchange -> {
            String path = exchange.getRequestURI().getPath();
            int id = Integer.parseInt(path.substring(prefix.length()));
            int call = calls.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            reply(exchange, behaviour.get().apply(id, call));
        });
        server.start();
        return server;
    }

    private static void reply(HttpExchange exchange, Reply reply) throws IOException {
        try {
            if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The client gave up (timeout tests).
        } finally {
            exchange.close();
        }
    }

    String ingestionUrl() {
        return "http://127.0.0.1:" + ingestion.getAddress().getPort();
    }

    String indexingUrl() {
        return "http://127.0.0.1:" + indexing.getAddress().getPort();
    }

    void stopIndexing() {
        indexing.stop(0);
    }

    int ingestCount(int id) {
        return ingestCalls.getOrDefault(id, new AtomicInteger()).get();
    }

    int indexCount(int id) {
        return indexCalls.getOrDefault(id, new AtomicInteger()).get();
    }

    /** Fast timings for tests. Each call uses the given timeout and lease. */
    Config config(Path db, int workers, Duration timeout, Duration lease) {
        return new Config(0, db, ingestionUrl(), indexingUrl(), workers, 3, timeout, timeout, lease, lease,
                Duration.ofMillis(20), Duration.ofMillis(50), Duration.ofMillis(10), Duration.ofMillis(100), 0);
    }

    @Override
    public void close() {
        ingestion.stop(0);
        indexing.stop(0);
        pool.shutdownNow();
    }
}
