package com.inverseek.search;

import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

public final class SearchApi {
    private static final Logger LOG = LoggerFactory.getLogger(SearchApi.class);
    private static final String HANDLED = "inverseek.search.handled";
    private final com.google.gson.Gson gson = new GsonBuilder().serializeNulls().create();
    private final QueryParser parser;
    private final SearchService service;

    public SearchApi(Config config, SearchService service) {
        this.parser = new QueryParser(config);
        this.service = service;
    }

    public void register(Javalin app) {
        app.get("/search", ctx -> json(ctx, 200, service.search(parser.parse(ctx.queryParamMap()))));
        app.get("/books/{book_id}", ctx -> {
            int id = QueryParser.bookId(ctx.pathParam("book_id"));
            var book = service.book(id);
            if (book.isPresent()) json(ctx, 200, book.get());
            else error(ctx, 404, "book_not_found", "Book not found: " + id);
        });
        app.get("/search/filters", ctx -> json(ctx, 200, service.filters()));
        app.get("/health", ctx -> {
            var state = service.health();
            ThreadPool pool = app.jettyServer().server().getThreadPool();
            int maxThreads = ((ThreadPool.SizedThreadPool) pool).getMaxThreads();
            json(ctx, 200, new Health("search", "UP", state.version(), state.booksIndexed(), maxThreads));
        });
        app.exception(InvalidQueryException.class, (e, ctx) -> error(ctx, 400, e.code(), e.getMessage()));
        app.exception(IndexUnavailableException.class, (e, ctx) -> {
            LOG.warn("Index unavailable: {}", e.code(), e.getCause());
            error(ctx, 503, e.code(), e.getMessage());
        });
        app.exception(SQLException.class, (e, ctx) -> {
            LOG.warn("Index read failed", e);
            error(ctx, 503, "index_unavailable", "Cannot read the published index");
        });
        app.exception(HttpResponseException.class, (e, ctx) -> {
            String code = switch (e.getStatus()) {
                case 404 -> "route_not_found";
                case 405 -> "method_not_allowed";
                default -> "http_error";
            };
            error(ctx, e.getStatus(), code, e.getMessage());
        });
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unexpected Search failure", e);
            error(ctx, 500, "internal_error", "Unexpected search error");
        });
        app.error(404, ctx -> {
            if (ctx.attribute(HANDLED) == null)
                error(ctx, 404, "route_not_found", "No route " + ctx.method() + " " + ctx.path());
        });
    }

    private void error(Context ctx, int status, String code, String message) {
        json(ctx, status, new Error(code, message));
    }

    private void json(Context ctx, int status, Object value) {
        ctx.attribute(HANDLED, true);
        ctx.status(status).contentType("application/json").result(gson.toJson(value));
    }

    public record Error(String error, String message) {}
    public record Health(String service, String status, long index_version, long books_indexed, int max_threads) {}
}
