package com.inverseek.search;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class SearchApiTest {
    @TempDir Path root;
    Path database;
    Javalin app;
    String base;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void start() throws Exception {
        database = TestIndex.create(root);
        restart(TestIndex.config(database));
    }

    void restart(Config config) {
        if (app != null) app.stop();
        app = App.create(config).start(0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach void stop() {
        if (app != null) app.stop();
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static JsonObject json(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    @Test void returnsContractWithNumericIdsNullsAndOnlySuppliedFilters() throws Exception {
        var response = get("/search?q=love&language=EN&limit=2");
        assertEquals(200, response.statusCode());
        var body = json(response);
        assertEquals("love", body.get("query").getAsString());
        assertEquals("en", body.getAsJsonObject("filters").get("language").getAsString());
        assertEquals(1, body.getAsJsonObject("filters").size());
        assertEquals(3, body.get("total").getAsLong());
        assertEquals(16, body.get("index_version").getAsLong());
        assertEquals(2, body.getAsJsonArray("results").size());
        var book = body.getAsJsonArray("results").get(1).getAsJsonObject();
        assertTrue(book.getAsJsonPrimitive("book_id").isNumber());
        assertEquals(10, book.get("book_id").getAsInt());
        assertTrue(book.has("year"));
        assertTrue(book.get("year").isJsonNull());
        assertTrue(book.get("score").getAsDouble() > 0);
        assertFalse(book.has("body_path"));
    }

    @Test void validEmptySearchAndOffsetBeyondTotalRemainSuccessful() throws Exception {
        var response = get("/search?q=unknownterm");
        assertEquals(200, response.statusCode());
        assertEquals(0, json(response).get("total").getAsLong());
        assertEquals(0, json(response).getAsJsonArray("results").size());
        var offset = get("/search?q=love&offset=999");
        assertEquals(200, offset.statusCode());
        assertEquals(5, json(offset).get("total").getAsLong());
        assertEquals(0, json(offset).getAsJsonArray("results").size());
    }

    @Test void invalidInputsReturn400Json() throws Exception {
        for (String path : List.of("/search", "/search?q=a", "/search?q=", "/search?q=love&year=0",
                "/search?q=love&year=2101", "/search?q=love&year=hello", "/search?q=love&language=english",
                "/search?q=love&offset=-1", "/search?q=love&offset=hello", "/search?q=love&limit=0",
                "/search?q=love&limit=101", "/search?q=love&limit=hello",
                "/search?q=love&author=" + "a".repeat(101),
                "/search?q=one%20two%20three%20four%20five%20six%20seven%20eight%20nine%20ten%20eleven",
                "/search?q=love&q=book", "/books/abc", "/books/0", "/books/10000001")) {
            var response = get(path);
            assertEquals(400, response.statusCode(), path);
            var body = json(response);
            assertTrue(body.has("error"));
            assertTrue(body.has("message"));
        }
        assertEquals(200, get("/search?q=love&future=value").statusCode());
    }

    @Test void bookDetailsFiltersHealthAndUnknownRouteFollowContract() throws Exception {
        var response = get("/books/300");
        assertEquals(200, response.statusCode());
        var book = json(response);
        assertEquals(300, book.get("book_id").getAsInt());
        assertTrue(book.get("author").isJsonNull());
        assertTrue(book.get("year").isJsonNull());
        assertTrue(book.has("word_count"));
        assertTrue(book.has("indexed_at"));
        assertFalse(book.has("content_sha"));
        assertFalse(book.has("body_path"));
        var missing = get("/books/999");
        assertEquals(404, missing.statusCode());
        assertEquals("book_not_found", json(missing).get("error").getAsString());
        var filters = json(get("/search/filters"));
        assertEquals(6, filters.get("books_indexed").getAsLong());
        assertEquals(16, filters.get("index_version").getAsLong());
        assertEquals(3, filters.getAsJsonArray("languages").size());
        var health = json(get("/health"));
        assertEquals("search", health.get("service").getAsString());
        assertEquals("UP", health.get("status").getAsString());
        assertEquals(32, health.get("max_threads").getAsInt());
        assertEquals(16, health.get("index_version").getAsLong());
        var unknown = get("/missing");
        assertEquals(404, unknown.statusCode());
        assertEquals("route_not_found", json(unknown).get("error").getAsString());
    }

    @Test void startsWithoutDatabaseAndReturns503WithoutCreatingIt() throws Exception {
        Path absent = root.resolve("missing/database.db");
        restart(TestIndex.config(absent));
        for (String path : List.of("/search?q=love", "/books/2", "/search/filters", "/health")) {
            var response = get(path);
            assertEquals(503, response.statusCode());
            assertEquals("index_not_ready", json(response).get("error").getAsString());
        }
        assertFalse(Files.exists(absent));
        assertFalse(Files.exists(absent.getParent()));
    }

    @Test void incompatibleAndUninitializedIndexesReturn503() throws Exception {
        try (var connection = TestIndex.writer(database); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=0");
            var incompatible = get("/search?q=love");
            assertEquals(503, incompatible.statusCode());
            assertEquals("index_incompatible", json(incompatible).get("error").getAsString());
            statement.execute("PRAGMA user_version=1");
            statement.execute("DELETE FROM index_state");
            var uninitialized = get("/health");
            assertEquals(503, uninitialized.statusCode());
            assertEquals("index_not_ready", json(uninitialized).get("error").getAsString());
        }
    }

    @Test void corsSupportsWildcardAndPreflight() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/search?q=love"))
                .header("Origin", "http://localhost:5173").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var preflight = client.send(HttpRequest.newBuilder(URI.create(base + "/search"))
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(preflight.statusCode() >= 200 && preflight.statusCode() < 300);
        assertEquals("*", preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @Test void corsRestrictsOriginsAndStillCoversErrorResponses() throws Exception {
        var config = TestIndex.config(database);
        restart(new Config(0, database, 20, 100, List.of("http://localhost:5173"), 5000, config.maxThreads()));
        var allowed = client.send(HttpRequest.newBuilder(URI.create(base + "/search?q=a"))
                .header("Origin", "http://localhost:5173").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, allowed.statusCode());
        assertEquals("http://localhost:5173",
                allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var disallowed = client.send(HttpRequest.newBuilder(URI.create(base + "/search?q=love"))
                .header("Origin", "https://unapproved.example").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(disallowed.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test void concurrentRequestsSeeEitherCompletePublishedVersion() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<JsonObject>> responses = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                responses.add(pool.submit(() -> {
                    var response = get("/search?q=love");
                    assertEquals(200, response.statusCode());
                    return json(response);
                }));
                if (i == 8) TestIndex.publishExtraBook(database);
            }
            for (var response : responses) {
                var body = response.get();
                long version = body.get("index_version").getAsLong();
                long total = body.get("total").getAsLong();
                assertTrue((version == 16 && total == 5) || (version == 17 && total == 6));
                assertEquals(total, body.getAsJsonArray("results").size());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
