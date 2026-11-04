package com.inverseek.ingestion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Downloads books from Project Gutenberg over HTTP.
 *
 * <p>Two URL patterns are tried in order, because older books are not always available under the
 * first one. A 404 on every pattern means the book has no plain-text file.
 */
public class HttpBookSource implements BookSource {

    private static final String USER_AGENT = "InverSeek-Ingestion/1.0 (university project)";

    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;
    private final long maxBytes;

    public HttpBookSource(String baseUrl, Duration timeout, long maxBytes) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    List<String> candidateUrls(int bookId) {
        return List.of(
                baseUrl + "/cache/epub/" + bookId + "/pg" + bookId + ".txt",
                baseUrl + "/files/" + bookId + "/" + bookId + "-0.txt");
    }

    @Override
    public String fetch(int bookId) {
        for (String url : candidateUrls(bookId)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw new SourceUnavailableException("Timeout downloading book " + bookId, true, e);
            } catch (IOException e) {
                throw new SourceUnavailableException("Network error downloading book " + bookId + ": " + e.getMessage(), false, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceUnavailableException("Interrupted downloading book " + bookId, false, e);
            }

            int status = response.statusCode();
            if (status == 404 || status == 410) {
                drain(response);
                continue;
            }
            if (status != 200) {
                drain(response);
                throw new SourceUnavailableException("Gutenberg answered HTTP " + status + " for book " + bookId, false, null);
            }
            return readLimited(response.body(), bookId);
        }
        throw new BookNotFoundException("Book " + bookId + " has no plain-text file on Project Gutenberg");
    }

    private String readLimited(InputStream in, int bookId) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new InvalidBookException("Book " + bookId + " is larger than " + maxBytes + " bytes");
                }
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (HttpTimeoutException e) {
            throw new SourceUnavailableException("Timeout reading book " + bookId, true, e);
        } catch (IOException e) {
            throw new SourceUnavailableException("Network error reading book " + bookId + ": " + e.getMessage(), false, e);
        }
    }

    private static void drain(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            in.transferTo(OutputStreamSink.INSTANCE);
        } catch (IOException ignored) {
            // The connection is discarded anyway.
        }
    }

    /** Discards bytes; avoids allocating a buffer for error responses. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
