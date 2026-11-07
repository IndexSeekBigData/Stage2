package com.inverseek.indexing;

import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexingApiTest {
    @TempDir Path root;
    Javalin app;
    String base;
    HttpClient client=HttpClient.newHttpClient();

    @BeforeEach void start() throws Exception {
        Path lake=Files.createDirectories(root.resolve("lake"));
        PublishedBookFixture.create(lake,"20261104/17",123,"Title: Test\nAuthor: José Álvarez\nLanguage: Spanish\n",
                "love love books","2026-11-04T17:00:00Z");
        app=App.create(new Config(0,lake,root.resolve("index.db"),32)).start(0);
        base="http://127.0.0.1:"+app.port();
    }
    @AfterEach void stop() { if(app!=null) app.stop(); }
    HttpResponse<String> request(String method,String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base+path))
                .method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void indexesUpdatesRebuildsAndReportsPublishedVersion() throws Exception {
        var result=request("POST","/index");
        assertEquals(200,result.statusCode());
        assertEquals(1,JsonParser.parseString(result.body()).getAsJsonObject().get("indexed").getAsInt());
        assertEquals(200,request("POST","/index/update/123").statusCode());
        assertEquals(200,request("POST","/index/rebuild").statusCode());
        var status=JsonParser.parseString(request("GET","/index/status").body()).getAsJsonObject();
        assertEquals(1,status.get("books_indexed").getAsInt());
        assertEquals(3,status.get("index_version").getAsLong());
        var health=JsonParser.parseString(request("GET","/health").body()).getAsJsonObject();
        assertEquals(32,health.get("max_threads").getAsInt());
        assertEquals(3,health.get("index_version").getAsLong());
    }

    @Test void updateReturnsNumericBookId() throws Exception {
        var body=JsonParser.parseString(request("POST","/index/update/123").body()).getAsJsonObject();
        assertTrue(body.get("book_id").getAsJsonPrimitive().isNumber());
        assertEquals(123,body.get("book_id").getAsInt());
        assertEquals("updated",body.get("index").getAsString());
    }

    @Test void corruptMarkerOfAnotherBookDoesNotBlockUpdates() throws Exception {
        Path lake=root.resolve("lake");
        Path corrupt=PublishedBookFixture.create(lake,"20261104/17",84,"Title: Other\n","other book","2026-11-04T17:00:00Z");
        Files.writeString(corrupt,"{broken");
        assertEquals(200,request("POST","/index/update/123").statusCode());
        assertEquals(500,request("POST","/index/update/84").statusCode());
        var bulk=JsonParser.parseString(request("POST","/index").body()).getAsJsonObject();
        assertEquals(1,bulk.get("indexed").getAsInt());
        assertEquals(1,bulk.get("failed").getAsInt());
        assertEquals(500,request("POST","/index/rebuild").statusCode());
    }

    @Test void returnsStructuredErrorsForInvalidMissingAndUnknownRoutes() throws Exception {
        for (String id : new String[]{"abc","0","-1","10000001"}) {
            var response=request("POST","/index/update/"+id);
            assertEquals(400,response.statusCode());
            var error=JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals("invalid_book_id",error.get("error").getAsString());
            assertTrue(error.has("message"));
        }
        var missing=request("POST","/index/update/456");
        assertEquals(404,missing.statusCode());
        assertEquals("book_not_found",JsonParser.parseString(missing.body()).getAsJsonObject().get("error").getAsString());
        var unknown=request("GET","/missing");
        assertEquals(404,unknown.statusCode());
        assertEquals("route_not_found",JsonParser.parseString(unknown.body()).getAsJsonObject().get("error").getAsString());
    }
}
