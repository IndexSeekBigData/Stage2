package com.inverseek.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpPipeline implements Pipeline {
    private final Config config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HttpPipeline(Config config) {
        this.config = config;
    }

    private HttpResponse<String> post(String url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Outcome failure(IOException e, String service) {
        // java.net.http may wrap a refused connection, so the cause is checked too.
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException || e.getCause() instanceof ConnectException)
            return new Outcome(Kind.UNREACHABLE, service + " unreachable");
        return new Outcome(Kind.RETRYABLE, service + " call failed: " + e);
    }

    private static Outcome interrupted(String service) {
        Thread.currentThread().interrupt();
        return new Outcome(Kind.UNREACHABLE, service + " call interrupted");
    }

    private static String shortBody(String body) {
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    @Override
    public Outcome ingest(int bookId) {
        HttpResponse<String> response;
        try {
            response = post(config.ingestionUrl() + "/ingest/" + bookId, config.ingestionTimeout());
        } catch (IOException e) {
            return failure(e, "Ingestion");
        } catch (InterruptedException e) {
            return interrupted("Ingestion");
        }
        String detail = "Ingestion HTTP " + response.statusCode() + ": " + shortBody(response.body());
        return switch (response.statusCode()) {
            case 200 -> ingestSuccess(response.body());
            case 404, 422 -> new Outcome(Kind.NOT_FOUND, detail);
            case 400 -> new Outcome(Kind.REJECTED, detail);
            default -> new Outcome(Kind.RETRYABLE, detail);
        };
    }

    private static Outcome ingestSuccess(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String status = json.get("status").getAsString();
            if (!status.equals("downloaded") && !status.equals("available"))
                return new Outcome(Kind.RETRYABLE, "Unexpected ingestion status: " + status);
            return new Outcome(Kind.SUCCESS, json.get("path").getAsString());
        } catch (JsonParseException | IllegalStateException | NullPointerException e) {
            return new Outcome(Kind.RETRYABLE, "Unexpected ingestion response: " + shortBody(body));
        }
    }

    @Override
    public Outcome index(int bookId) {
        HttpResponse<String> response;
        try {
            response = post(config.indexingUrl() + "/index/update/" + bookId, config.indexingTimeout());
        } catch (IOException e) {
            return failure(e, "Indexing");
        } catch (InterruptedException e) {
            return interrupted("Indexing");
        }
        String detail = "Indexing HTTP " + response.statusCode() + ": " + shortBody(response.body());
        return switch (response.statusCode()) {
            case 200 -> new Outcome(Kind.SUCCESS, null);
            case 400 -> new Outcome(Kind.REJECTED, detail);
            default -> new Outcome(Kind.RETRYABLE, detail);
        };
    }

    /** Reachability of each service, for /health. */
    public Map<String, String> dependencies() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("ingestion", reach(config.ingestionUrl()));
        result.put("indexing", reach(config.indexingUrl()));
        return result;
    }

    private String reach(String base) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(2)).GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 ? "UP" : "HTTP " + status;
        } catch (IOException e) {
            return "DOWN";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "UNKNOWN";
        }
    }
}
