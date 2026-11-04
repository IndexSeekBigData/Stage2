package com.inverseek.ingestion;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP endpoints of the Ingestion Service. All responses are JSON.
 *
 * <ul>
 *   <li>{@code POST /ingest/{book_id}} - internal, called by the Control Module</li>
 *   <li>{@code GET /ingest/status/{book_id}} - internal</li>
 *   <li>{@code GET /ingest/list} - internal / administrative</li>
 *   <li>{@code GET /health} - operational</li>
 * </ul>
 */
public final class IngestionApi {

    private static final Logger log = LoggerFactory.getLogger(IngestionApi.class);
    static final int MAX_BOOK_ID = 10_000_000;
    /** Marks responses written by this class, so the 404 fallback does not overwrite them. */
    private static final String HANDLED = "inverseek.handled";

    private final IngestionService service;
    private final Gson gson = new Gson();

    public IngestionApi(IngestionService service) {
        this.service = service;
    }

    public void register(Javalin app) {
        app.post("/ingest/{book_id}", this::ingest);
        app.get("/ingest/status/{book_id}", this::status);
        app.get("/ingest/list", this::list);
        app.get("/health", ctx -> health(ctx, app));

        app.exception(InvalidBookIdException.class, (e, ctx) ->
                error(ctx, 400, "invalid_book_id", e.getMessage(), null));
        app.exception(BookNotFoundException.class, (e, ctx) ->
                error(ctx, 404, "book_not_found", e.getMessage(), bookId(ctx)));
        app.exception(InvalidBookException.class, (e, ctx) ->
                error(ctx, 422, "invalid_book_format", e.getMessage(), bookId(ctx)));
        app.exception(SourceUnavailableException.class, (e, ctx) -> {
            log.warn("Upstream failure: {}", e.getMessage());
            if (e.isTimeout()) {
                error(ctx, 504, "upstream_timeout", e.getMessage(), bookId(ctx));
            } else {
                error(ctx, 502, "upstream_error", e.getMessage(), bookId(ctx));
            }
        });
        app.exception(UncheckedIOException.class, (e, ctx) -> {
            log.error("Storage failure", e);
            error(ctx, 500, "storage_error", e.getMessage(), bookId(ctx));
        });
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unexpected failure", e);
            error(ctx, 500, "internal_error", "Unexpected error: " + e.getClass().getSimpleName(), bookId(ctx));
        });
        app.error(404, ctx -> {
            if (ctx.attribute(HANDLED) == null) {
                error(ctx, 404, "route_not_found", "No route " + ctx.method() + " " + ctx.path(), null);
            }
        });
    }

    private void ingest(Context ctx) {
        int bookId = parseBookId(ctx);
        IngestionService.Result result = service.ingest(bookId);
        Map<String, Object> body = bookJson(result.book());
        body.put("status", result.outcome() == IngestionService.Outcome.DOWNLOADED ? "downloaded" : "available");
        body.put("elapsed_ms", result.elapsedMillis());
        json(ctx, 200, body);
    }

    private void status(Context ctx) {
        int bookId = parseBookId(ctx);
        service.status(bookId).ifPresentOrElse(
                book -> {
                    Map<String, Object> body = bookJson(book);
                    body.put("status", "available");
                    json(ctx, 200, body);
                },
                () -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("book_id", bookId);
                    body.put("status", "not_available");
                    json(ctx, 404, body);
                });
    }

    private void list(Context ctx) {
        List<Integer> ids = service.list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", ids.size());
        body.put("books", ids);
        json(ctx, 200, body);
    }

    private void health(Context ctx, Javalin app) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "ingestion");
        body.put("status", "UP");
        body.put("books_available", service.list().size());
        body.put("downloads_since_start", service.downloadsSinceStart());
        ThreadPool pool = app.jettyServer().server().getThreadPool();
        if (pool instanceof ThreadPool.SizedThreadPool sized) {
            body.put("max_threads", sized.getMaxThreads());
        }
        json(ctx, 200, body);
    }

    private static Map<String, Object> bookJson(BookRecord book) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("book_id", book.bookId());
        body.put("status", null);
        body.put("path", book.path());
        body.put("header_path", book.headerPath());
        body.put("body_path", book.bodyPath());
        body.put("header_bytes", book.headerBytes());
        body.put("body_bytes", book.bodyBytes());
        body.put("body_sha256", book.bodySha256());
        body.put("ingested_at", book.ingestedAt());
        return body;
    }

    static int parseBookId(Context ctx) {
        String raw = ctx.pathParam("book_id");
        int id;
        try {
            id = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new InvalidBookIdException("book_id must be a positive integer, got '" + raw + "'");
        }
        if (id <= 0 || id > MAX_BOOK_ID) {
            throw new InvalidBookIdException("book_id must be between 1 and " + MAX_BOOK_ID + ", got " + id);
        }
        return id;
    }

    private static Integer bookId(Context ctx) {
        try {
            return Integer.parseInt(ctx.pathParam("book_id"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void error(Context ctx, int status, String code, String message, Integer bookId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        if (bookId != null) {
            body.put("book_id", bookId);
        }
        json(ctx, status, body);
    }

    private void json(Context ctx, int status, Map<String, Object> body) {
        ctx.attribute(HANDLED, Boolean.TRUE);
        ctx.status(status).contentType("application/json").result(gson.toJson(body));
    }

    /** Raised for a malformed or out-of-range book id in the path. */
    static final class InvalidBookIdException extends RuntimeException {
        InvalidBookIdException(String message) {
            super(message);
        }
    }
}
