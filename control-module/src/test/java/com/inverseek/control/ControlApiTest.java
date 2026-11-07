package com.inverseek.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlApiTest {
    @TempDir Path root;
    FakeServices services;
    App.Running running;
    String base;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void setUp() throws Exception {
        services = new FakeServices();
        Config config = services.config(root.resolve("control.db"), 2, Duration.ofSeconds(5), Duration.ofSeconds(5));
        running = App.create(config);
        running.start(0);
        base = "http://127.0.0.1:" + running.app().port();
    }

    @AfterEach void tearDown() {
        running.stop();
        services.close();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject json(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void awaitState(int id, String state) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var response = send("GET", "/control/books/" + id, null);
            if (response.statusCode() == 200 && json(response).get("state").getAsString().equals(state)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Book " + id + " never reached " + state);
    }

    @Test void registersIdsAndRangesIdempotently() throws Exception {
        var first = send("POST", "/control/books", "{\"ids\":[1,2,2]}");
        assertEquals(202, first.statusCode());
        assertEquals(2, json(first).get("inserted").getAsInt());
        assertEquals(0, json(first).get("ignored").getAsInt());

        var range = json(send("POST", "/control/books", "{\"from\":2,\"to\":4}"));
        assertEquals(2, range.get("inserted").getAsInt());
        assertEquals(1, range.get("ignored").getAsInt());
    }

    @Test void rejectsInvalidRegistrations() throws Exception {
        String[][] cases = {
                {"not json", "invalid_request"},
                {"[1,2]", "invalid_request"},
                {"{}", "invalid_request"},
                {"{\"ids\":[]}", "invalid_request"},
                {"{\"ids\":5}", "invalid_request"},
                {"{\"ids\":[1],\"from\":1,\"to\":2}", "invalid_request"},
                {"{\"from\":1}", "invalid_request"},
                {"{\"from\":5,\"to\":1}", "invalid_request"},
                {"{\"ids\":[0]}", "invalid_book_id"},
                {"{\"ids\":[1.5]}", "invalid_book_id"},
                {"{\"ids\":[\"abc\"]}", "invalid_book_id"},
                {"{\"ids\":[10000001]}", "invalid_book_id"},
                {"{\"from\":1,\"to\":10001}", "too_many_books"},
        };
        for (String[] c : cases) {
            var response = send("POST", "/control/books", c[0]);
            assertEquals(400, response.statusCode(), c[0]);
            assertEquals(c[1], json(response).get("error").getAsString(), c[0]);
        }
    }

    @Test void reportsABookThroughThePipeline() throws Exception {
        send("POST", "/control/books", "{\"ids\":[1342]}");
        awaitState(1342, "INDEXED");

        JsonObject book = json(send("GET", "/control/books/1342", null));
        assertEquals(1342, book.get("book_id").getAsInt());
        assertEquals("20261105/10/1342", book.get("datalake_path").getAsString());
        assertTrue(book.get("last_error").isJsonNull());
        assertFalse(book.get("indexed_at").isJsonNull());
        assertEquals(1, book.get("attempts").getAsInt());
    }

    @Test void unknownAndInvalidBooksAreRejected() throws Exception {
        assertEquals(404, send("GET", "/control/books/5", null).statusCode());
        assertEquals("book_not_found", json(send("GET", "/control/books/5", null)).get("error").getAsString());
        assertEquals(400, send("GET", "/control/books/abc", null).statusCode());
        assertEquals(400, send("POST", "/control/books/0/retry", null).statusCode());
        assertEquals(404, send("POST", "/control/books/5/retry", null).statusCode());
        assertEquals("route_not_found", json(send("GET", "/missing", null)).get("error").getAsString());
    }

    @Test void retryReindexesAndRestartsFinalBooks() throws Exception {
        services.ingest = (id, call) -> call == 1
                ? new FakeServices.Reply(404, "{}", 0)
                : FakeServices.Reply.ok("{\"status\":\"downloaded\",\"path\":\"p/" + id + "\"}");
        send("POST", "/control/books", "{\"ids\":[7]}");
        awaitState(7, "NOT_FOUND");

        assertEquals(202, send("POST", "/control/books/7/retry", null).statusCode());
        awaitState(7, "INDEXED");
        assertEquals(2, services.ingestCount(7));
        assertEquals(1, services.indexCount(7));

        assertEquals(202, send("POST", "/control/books/7/retry", null).statusCode());
        long deadline = System.currentTimeMillis() + 10_000;
        while (services.indexCount(7) < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        awaitState(7, "INDEXED");
        assertEquals(2, services.ingestCount(7), "Reindexing does not download again");
        assertEquals(2, services.indexCount(7));
    }

    @Test void retryOfAnActiveBookIsAConflict() throws Exception {
        services.ingest = (id, call) -> new FakeServices.Reply(200, "{\"status\":\"downloaded\",\"path\":\"p\"}", 1000);
        send("POST", "/control/books", "{\"ids\":[8]}");
        var response = send("POST", "/control/books/8/retry", null);
        assertEquals(409, response.statusCode());
        assertEquals("not_retryable", json(response).get("error").getAsString());
    }

    @Test void listsWithFiltersAndValidatesParameters() throws Exception {
        send("POST", "/control/books", "{\"ids\":[1,2,3]}");
        awaitState(1, "INDEXED");
        awaitState(2, "INDEXED");
        awaitState(3, "INDEXED");

        JsonObject page = json(send("GET", "/control/books?state=indexed&offset=1&limit=1", null));
        assertEquals(3, page.get("total").getAsInt());
        assertEquals(1, page.getAsJsonArray("books").size());
        assertEquals(2, page.getAsJsonArray("books").get(0).getAsJsonObject().get("book_id").getAsInt());

        assertEquals(0, json(send("GET", "/control/books?state=FAILED", null)).get("total").getAsInt());
        for (String query : new String[]{"state=NOPE", "limit=0", "limit=501", "offset=-1", "limit=abc"}) {
            var response = send("GET", "/control/books?" + query, null);
            assertEquals(400, response.statusCode(), query);
            assertEquals("invalid_parameter", json(response).get("error").getAsString(), query);
        }
    }

    @Test void statusAndMetricsDescribeTheRun() throws Exception {
        assertEquals(0, json(send("GET", "/control/metrics", null)).get("books_indexed").getAsInt());

        send("POST", "/control/books", "{\"ids\":[1,2]}");
        awaitState(1, "INDEXED");
        awaitState(2, "INDEXED");

        JsonObject status = json(send("GET", "/control/status", null));
        assertEquals(2, status.get("total").getAsInt());
        assertEquals(0, status.get("active").getAsInt());
        assertEquals(2, status.getAsJsonObject("states").get("INDEXED").getAsInt());
        assertEquals(2, status.get("workers").getAsInt());
        assertEquals(0, status.get("active_leases").getAsInt());

        JsonObject metrics = json(send("GET", "/control/metrics", null));
        assertEquals(2, metrics.get("books_indexed").getAsInt());
        for (String phase : new String[]{"queue_wait_ms", "ingest_ms", "index_wait_ms", "index_ms", "end_to_end_ms"}) {
            assertNotNull(metrics.getAsJsonObject(phase).get("avg"), phase);
            assertFalse(metrics.getAsJsonObject(phase).get("p95").isJsonNull(), phase);
        }
    }

    @Test void healthReportsDependencies() throws Exception {
        JsonObject health = json(send("GET", "/health", null));
        assertEquals("UP", health.get("status").getAsString());
        assertEquals("UP", health.getAsJsonObject("dependencies").get("ingestion").getAsString());

        services.stopIndexing();
        health = json(send("GET", "/health", null));
        assertEquals("DOWN", health.getAsJsonObject("dependencies").get("indexing").getAsString());
    }

    @Test void configValidatesLeasesAgainstTimeouts() {
        var env = new java.util.HashMap<String, String>();
        assertEquals(7000, Config.load(env).port());
        assertEquals(4, Config.load(env).workers());
        env.put("CONTROL_PORT", "7100");
        env.put("CONTROL_LEASE_INGEST_SECONDS", "90");
        assertThrows(IllegalArgumentException.class, () -> Config.load(env));
        env.put("CONTROL_LEASE_INGEST_SECONDS", "91");
        assertEquals(7100, Config.load(env).port());
    }
}
