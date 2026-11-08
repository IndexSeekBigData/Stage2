package com.inverseek.systemtests.support;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public final class Api {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final OpenApi schemas;
    private final Path evidence;
    private final AtomicInteger sequence = new AtomicInteger();

    public Api(Env env) throws Exception {
        schemas = new OpenApi(env.root());
        evidence = env.data().resolve("evidence/http-" + System.getProperty("system.phase", System.getenv().getOrDefault("SYSTEM_PHASE", "tests")));
        Files.createDirectories(evidence);
    }
    public Reply call(String service, String base, String method, String path, String body) throws Exception {
        return call(service, base, method, path, body, Map.of(), true);
    }
    public Reply call(String service, String base, String method, String path, String body,
                      Map<String,String> headers, boolean validate) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        headers.forEach(request::header);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        var record = new com.google.gson.JsonObject();
        record.addProperty("service", service); record.addProperty("method", method); record.addProperty("path", path);
        record.addProperty("status", response.statusCode()); record.addProperty("body", response.body());
        record.add("headers", new com.google.gson.Gson().toJsonTree(response.headers().map()));
        Files.writeString(evidence.resolve(String.format("%05d.json", sequence.incrementAndGet())), record.toString());
        if (validate) schemas.validate(service, path, method, response);
        return new Reply(response.statusCode(), response.body(), response.headers());
    }
    public Reply get(String service, String base, String path) throws Exception { return call(service, base, "GET", path, null); }
    public Reply post(String service, String base, String path, String body) throws Exception { return call(service, base, "POST", path, body); }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public record Reply(int status, String body, HttpHeaders headers) {
        public JsonObject json() { return JsonParser.parseString(body).getAsJsonObject(); }
        public JsonObject expect(int expected) { assertEquals(expected, status, body); return json(); }
    }
}
