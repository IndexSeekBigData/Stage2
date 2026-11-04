package com.inverseek.ingestion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the HTTP contract, with a local fake Gutenberg server:
 * <ul>
 *   <li>/cache/epub/1342/pg1342.txt -> sample book</li>
 *   <li>/files/84/84-0.txt          -> sample book (only the fallback URL exists)</li>
 *   <li>/cache/epub/55/pg55.txt     -> text without Gutenberg markers</li>
 *   <li>/cache/epub/500/pg500.txt   -> HTTP 500</li>
 *   <li>/cache/epub/77/pg77.txt     -> answers after 3 s (timeout is 1 s)</li>
 *   <li>anything else               -> 404</li>
 * </ul>
 */
class IngestionApiTest {

    @TempDir
    Path datalake;

    private HttpServer gutenberg;
    private final AtomicInteger gutenbergHits = new AtomicInteger();
    private Javalin app;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    @BeforeEach
    void start() throws IOException {
        gutenberg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gutenberg.createContext("/", this::fakeGutenberg);
        gutenberg.start();
        String gutenbergUrl = "http://127.0.0.1:" + gutenberg.getAddress().getPort();

        Config config = new Config(0, datalake, gutenbergUrl, Duration.ofSeconds(1), 1_000_000, ZoneId.of("UTC"), 0);
        app = App.create(config, new HttpBookSource(gutenbergUrl, config.downloadTimeout(), config.maxBookBytes()));
        app.start(0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
        gutenberg.stop(0);
    }

    private void fakeGutenberg(HttpExchange exchange) throws IOException {
        gutenbergHits.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/cache/epub/1342/pg1342.txt", "/files/84/84-0.txt" -> reply(exchange, 200, TestFixtures.sampleBook());
            case "/cache/epub/55/pg55.txt" -> reply(exchange, 200, "no markers here");
            case "/cache/epub/500/pg500.txt" -> reply(exchange, 500, "boom");
            case "/cache/epub/77/pg77.txt" -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reply(exchange, 200, TestFixtures.sampleBook());
            }
            default -> reply(exchange, 404, "not found");
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // Client gave up (timeout test).
        }
    }

    private record Reply(int status, JsonObject json) {
    }

    private Reply call(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                "Every response must be JSON");
        return new Reply(response.statusCode(), gson.fromJson(response.body(), JsonObject.class));
    }

    @Test
    void ingestDownloadsStoresAndReportsThePath() throws Exception {
        Reply reply = call("POST", "/ingest/1342");

        assertEquals(200, reply.status());
        assertEquals("downloaded", reply.json().get("status").getAsString());
        assertEquals(1342, reply.json().get("book_id").getAsInt());
        String bodyPath = reply.json().get("body_path").getAsString();
        assertTrue(bodyPath.matches("\\d{8}/\\d{2}/1342\\.body\\.txt"), bodyPath);
        assertTrue(Files.exists(datalake.resolve(bodyPath)));
        assertTrue(Files.exists(datalake.resolve(reply.json().get("path").getAsString() + ".success.json")));
    }

    @Test
    void repeatedIngestIsIdempotent() throws Exception {
        Reply first = call("POST", "/ingest/1342");
        int hitsAfterFirst = gutenbergHits.get();
        Reply second = call("POST", "/ingest/1342");

        assertEquals(200, second.status());
        assertEquals("available", second.json().get("status").getAsString());
        assertEquals(first.json().get("path"), second.json().get("path"));
        assertEquals(hitsAfterFirst, gutenbergHits.get(), "No second download");
    }

    @Test
    void fallsBackToTheSecondGutenbergUrl() throws Exception {
        Reply reply = call("POST", "/ingest/84");
        assertEquals(200, reply.status());
        assertEquals("downloaded", reply.json().get("status").getAsString());
    }

    @Test
    void statusReportsAvailableAndNotAvailable() throws Exception {
        assertEquals(404, call("GET", "/ingest/status/1342").status());
        assertEquals("not_available", call("GET", "/ingest/status/1342").json().get("status").getAsString());

        call("POST", "/ingest/1342");
        Reply available = call("GET", "/ingest/status/1342");

        assertEquals(200, available.status());
        assertEquals("available", available.json().get("status").getAsString());
    }

    @Test
    void listReturnsDownloadedBooksInOrder() throws Exception {
        call("POST", "/ingest/1342");
        call("POST", "/ingest/84");

        Reply list = call("GET", "/ingest/list");

        assertEquals(200, list.status());
        assertEquals(2, list.json().get("count").getAsInt());
        assertEquals("[84,1342]", list.json().get("books").toString());
    }

    @Test
    void invalidBookIdIsRejected() throws Exception {
        assertEquals("invalid_book_id", call("POST", "/ingest/abc").json().get("error").getAsString());
        assertEquals(400, call("POST", "/ingest/abc").status());
        assertEquals(400, call("POST", "/ingest/0").status());
        assertEquals(400, call("GET", "/ingest/status/-5").status());
    }

    @Test
    void unknownBookIsNotFoundAndNothingIsStored() throws Exception {
        Reply reply = call("POST", "/ingest/999999");

        assertEquals(404, reply.status());
        assertEquals("book_not_found", reply.json().get("error").getAsString());
        assertEquals(0, call("GET", "/ingest/list").json().get("count").getAsInt());
    }

    @Test
    void fileWithoutMarkersIsRejected() throws Exception {
        Reply reply = call("POST", "/ingest/55");
        assertEquals(422, reply.status());
        assertEquals("invalid_book_format", reply.json().get("error").getAsString());
    }

    @Test
    void gutenbergFailureIsReportedAsRetryable() throws Exception {
        Reply reply = call("POST", "/ingest/500");
        assertEquals(502, reply.status());
        assertEquals("upstream_error", reply.json().get("error").getAsString());
    }

    @Test
    void slowGutenbergIsReportedAsTimeout() throws Exception {
        Reply reply = call("POST", "/ingest/77");
        assertEquals(504, reply.status());
        assertEquals("upstream_timeout", reply.json().get("error").getAsString());
        assertEquals(0, call("GET", "/ingest/list").json().get("count").getAsInt());
    }

    @Test
    void unknownRouteIsJsonNotFound() throws Exception {
        Reply reply = call("GET", "/nope");
        assertEquals(404, reply.status());
        assertEquals("route_not_found", reply.json().get("error").getAsString());
    }

    @Test
    void healthReportsUp() throws Exception {
        call("POST", "/ingest/1342");
        Reply health = call("GET", "/health");

        assertEquals(200, health.status());
        assertEquals("UP", health.json().get("status").getAsString());
        assertEquals(1, health.json().get("books_available").getAsInt());
        assertTrue(health.json().get("max_threads").getAsInt() > 0);
    }
}
