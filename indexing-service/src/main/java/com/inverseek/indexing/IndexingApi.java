
package com.inverseek.indexing;

import com.google.gson.Gson;
import io.javalin.Javalin;

public class IndexingApi {

    public static void register(
            Javalin app,
            IndexingService indexingService
    ) {

        Gson gson = new Gson();

        app.post("/index", ctx -> {

            try {
                IndexingService.IndexingResult result =
                        indexingService.indexBooks();

                ctx.contentType("application/json");
                ctx.result(gson.toJson(result));

            } catch (Exception e) {

                ctx.status(500);
                ctx.contentType("application/json");

                ctx.result(gson.toJson(
                        new ErrorResponse(e.getMessage())
                ));
            }
        });
    }

    public record ErrorResponse(String error) {
    }
}

