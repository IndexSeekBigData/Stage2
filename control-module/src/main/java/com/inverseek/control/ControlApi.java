package com.inverseek.control;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Administrative and operational API. All endpoints are consumed by an administrator or operations tools. */
public final class ControlApi {
    private static final Logger LOG = LoggerFactory.getLogger(ControlApi.class);
    private static final int MAX_BOOKS = 10_000;
    private static final int MAX_BOOK_ID = 10_000_000;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final String HANDLED = "inverseek.control.handled";

    private final ControlStore store;
    private final Config config;
    private final Supplier<Map<String, String>> dependencies;
    private final com.google.gson.Gson gson = new GsonBuilder().serializeNulls().create();

    public ControlApi(ControlStore store, Config config, Supplier<Map<String, String>> dependencies) {
        this.store = store;
        this.config = config;
        this.dependencies = dependencies;
    }

    private static final class BadRequest extends RuntimeException {
        final String code;

        BadRequest(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public void register(Javalin app) {
        app.post("/control/books", this::registerBooks);
        app.get("/control/books", this::listBooks);
        app.get("/control/books/{book_id}", this::getBook);
        app.post("/control/books/{book_id}/retry", this::retryBook);
        app.get("/control/status", this::status);
        app.get("/control/metrics", ctx -> json(ctx, 200, Metrics.from(store.indexedBooks())));
        app.get("/health", ctx -> health(ctx, app));

        app.exception(BadRequest.class, (e, ctx) -> error(ctx, 400, e.code, e.getMessage()));
        app.exception(SQLException.class, (e, ctx) -> {
            LOG.error("Control database failure", e);
            error(ctx, 500, "storage_error", "Cannot access the control database");
        });
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unexpected Control failure", e);
            error(ctx, 500, "internal_error", "Unexpected control error");
        });
        app.error(404, ctx -> {
            if (ctx.attribute(HANDLED) == null)
                error(ctx, 404, "route_not_found", "No route " + ctx.method() + " " + ctx.path());
        });
    }

    private void registerBooks(Context ctx) throws SQLException {
        Set<Integer> ids = parseIds(ctx.body());
        ControlStore.Registration result = store.register(ids);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inserted", result.inserted());
        body.put("ignored", result.ignored());
        body.put("total", ids.size());
        json(ctx, 202, body);
    }

    private static Set<Integer> parseIds(String raw) {
        JsonObject body;
        try {
            body = JsonParser.parseString(raw).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new BadRequest("invalid_request", "The body must be a JSON object with ids, or with from and to");
        }
        boolean hasIds = body.has("ids");
        boolean hasRange = body.has("from") || body.has("to");
        if (hasIds == hasRange)
            throw new BadRequest("invalid_request", "Use either ids, or from and to");
        Set<Integer> ids = new LinkedHashSet<>();
        if (hasIds) {
            if (!body.get("ids").isJsonArray())
                throw new BadRequest("invalid_request", "ids must be an array of integers");
            JsonArray array = body.getAsJsonArray("ids");
            if (array.isEmpty()) throw new BadRequest("invalid_request", "ids must not be empty");
            if (array.size() > MAX_BOOKS) throw new BadRequest("too_many_books", "At most " + MAX_BOOKS + " books per request");
            for (JsonElement element : array) ids.add(bookId(element));
        } else {
            if (!body.has("from") || !body.has("to")) throw new BadRequest("invalid_request", "from and to are both required");
            int from = bookId(body.get("from"));
            int to = bookId(body.get("to"));
            if (to < from) throw new BadRequest("invalid_request", "to must not be lower than from");
            if ((long) to - from + 1 > MAX_BOOKS) throw new BadRequest("too_many_books", "At most " + MAX_BOOKS + " books per request");
            for (int id = from; id <= to; id++) ids.add(id);
        }
        return ids;
    }

    private static int bookId(JsonElement element) {
        try {
            BigDecimal value = element.getAsJsonPrimitive().getAsBigDecimal();
            int id = value.intValueExact();
            if (id >= 1 && id <= MAX_BOOK_ID) return id;
        } catch (RuntimeException e) {
            // Reported below with the same message as an out-of-range id.
        }
        throw new BadRequest("invalid_book_id", "Book ids must be integers between 1 and " + MAX_BOOK_ID);
    }

    private static int pathBookId(Context ctx) {
        try {
            int id = Integer.parseInt(ctx.pathParam("book_id"));
            if (id >= 1 && id <= MAX_BOOK_ID) return id;
        } catch (NumberFormatException e) {
            // Reported below.
        }
        throw new BadRequest("invalid_book_id", "book_id must be an integer between 1 and " + MAX_BOOK_ID);
    }

    private void getBook(Context ctx) throws SQLException {
        int id = pathBookId(ctx);
        var book = store.find(id);
        if (book.isPresent()) json(ctx, 200, bookJson(book.get()));
        else error(ctx, 404, "book_not_found", "Book not registered: " + id);
    }

    private void listBooks(Context ctx) throws SQLException {
        String stateParam = ctx.queryParam("state");
        BookState state = null;
        if (stateParam != null && !stateParam.isBlank()) {
            try {
                state = BookState.valueOf(stateParam.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequest("invalid_parameter", "state must be one of " + Arrays.toString(BookState.values()));
            }
        }
        int offset = intParam(ctx, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = intParam(ctx, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        ControlStore.Page page = store.list(state, offset, limit);
        List<Map<String, Object>> books = new ArrayList<>();
        for (ControlStore.Book book : page.books()) books.add(bookJson(book));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", page.total());
        body.put("offset", offset);
        body.put("limit", limit);
        body.put("books", books);
        json(ctx, 200, body);
    }

    private static int intParam(Context ctx, String name, int fallback, int min, int max) {
        String raw = ctx.queryParam(name);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException e) {
            // Reported below.
        }
        throw new BadRequest("invalid_parameter", name + " must be an integer between " + min + " and " + max);
    }

    private void retryBook(Context ctx) throws SQLException {
        int id = pathBookId(ctx);
        switch (store.retry(id)) {
            case RETRIED -> json(ctx, 202, bookJson(store.find(id).orElseThrow()));
            case UNKNOWN -> error(ctx, 404, "book_not_found", "Book not registered: " + id);
            case NOT_RETRYABLE -> error(ctx, 409, "not_retryable", "Only FAILED, NOT_FOUND and INDEXED books can be retried");
        }
    }

    private void status(Context ctx) throws SQLException {
        Map<BookState, Long> counts = store.counts();
        long active = 0;
        long total = 0;
        Map<String, Long> states = new LinkedHashMap<>();
        for (var entry : counts.entrySet()) {
            states.put(entry.getKey().name(), entry.getValue());
            total += entry.getValue();
            if (entry.getKey().isActive()) active += entry.getValue();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", total);
        body.put("active", active);
        body.put("states", states);
        body.put("workers", config.workers());
        body.put("active_leases", store.activeLeases());
        json(ctx, 200, body);
    }

    private void health(Context ctx, Javalin app) throws SQLException {
        store.activeLeases();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "control");
        body.put("status", "UP");
        body.put("dependencies", dependencies.get());
        body.put("workers", config.workers());
        ThreadPool pool = app.jettyServer().server().getThreadPool();
        if (pool instanceof ThreadPool.SizedThreadPool sized) body.put("max_threads", sized.getMaxThreads());
        json(ctx, 200, body);
    }

    private static String time(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis).toString();
    }

    private static Map<String, Object> bookJson(ControlStore.Book book) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("book_id", book.bookId());
        body.put("state", book.state().name());
        body.put("attempts", book.attempts());
        body.put("last_error", book.lastError());
        body.put("datalake_path", book.datalakePath());
        body.put("lease_owner", book.leaseOwner());
        body.put("lease_until", time(book.leaseUntil()));
        body.put("queued_at", time(book.queuedAt()));
        body.put("download_started_at", time(book.downloadStartedAt()));
        body.put("downloaded_at", time(book.downloadedAt()));
        body.put("index_started_at", time(book.indexStartedAt()));
        body.put("indexed_at", time(book.indexedAt()));
        body.put("created_at", time(book.createdAt()));
        body.put("updated_at", time(book.updatedAt()));
        return body;
    }

    private void error(Context ctx, int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        json(ctx, status, body);
    }

    private void json(Context ctx, int status, Object value) {
        ctx.attribute(HANDLED, true);
        ctx.status(status).contentType("application/json").result(gson.toJson(value));
    }
}
