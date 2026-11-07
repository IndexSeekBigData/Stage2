package com.inverseek.indexing;

import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import org.slf4j.LoggerFactory;

public final class IndexingApi {
    private IndexingApi() {}

    public static void register(Javalin app,IndexingService service) {
        var gson=new GsonBuilder().serializeNulls().create();
        app.post("/index",ctx -> ctx.result(gson.toJson(service.indexBooks())));
        app.post("/index/rebuild",ctx -> ctx.result(gson.toJson(service.rebuildIndex())));
        app.get("/index/status",ctx -> ctx.result(gson.toJson(service.getStatus())));
        app.post("/index/update/{book_id}",ctx -> {
            String id=IndexingService.canonicalBookId(ctx.pathParam("book_id"));
            if (service.indexBook(id)) {
                ctx.result(gson.toJson(new UpdateResponse(Integer.parseInt(id),"updated")));
            } else {
                ctx.attribute("inverseek.handled",true);
                ctx.status(404).result(gson.toJson(new ErrorResponse("book_not_found","Book not found: "+id)));
            }
        });
        app.exception(IllegalArgumentException.class,(e,ctx) ->
                ctx.status(400).result(gson.toJson(new ErrorResponse("invalid_book_id",e.getMessage()))));
        app.exception(java.io.IOException.class,(e,ctx) -> {
            LoggerFactory.getLogger(IndexingApi.class).error("Datalake failure",e);
            ctx.status(500).result(gson.toJson(new ErrorResponse("datalake_error","Cannot read completed datalake books")));
        });
        app.exception(Exception.class,(e,ctx) -> {
            LoggerFactory.getLogger(IndexingApi.class).error("Indexing failure",e);
            ctx.status(500).result(gson.toJson(new ErrorResponse("internal_error","Indexing operation failed")));
        });
        app.error(404,ctx -> {
            if (ctx.attribute("inverseek.handled") == null)
                ctx.result(gson.toJson(new ErrorResponse("route_not_found","No route "+ctx.method()+" "+ctx.path())));
        });
    }

    public record UpdateResponse(int book_id,String index) {}
    public record ErrorResponse(String error,String message) {}
}
