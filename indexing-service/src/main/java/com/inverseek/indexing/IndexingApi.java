
package com.inverseek.indexing;

import com.google.gson.Gson;
import io.javalin.Javalin;

public class IndexingApi {


    public static void register(
            Javalin app,
            IndexingService indexingService
    ) {

        Gson gson = new Gson();

        // Index all available books
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

        // Index a specific book
        app.post("/index/update/{book_id}", ctx -> {

            String bookId = ctx.pathParam("book_id");

            try {
                boolean indexed =
                        indexingService.indexBook(bookId);

                ctx.contentType("application/json");

                if (indexed) {

                    ctx.result(gson.toJson(
                            new UpdateResponse(bookId, "updated")
                    ));

                } else {

                    ctx.status(404);

                    ctx.result(gson.toJson(
                            new ErrorResponse(
                                    "Book not found: " + bookId
                            )
                    ));
                }

            } catch (Exception e) {

                ctx.status(500);
                ctx.contentType("application/json");

                ctx.result(gson.toJson(
                        new ErrorResponse(e.getMessage())
                ));
            }
        });

        // Rebuild the complete index
        app.post("/index/rebuild", ctx -> {

            try {
                IndexingService.RebuildResult result =
                        indexingService.rebuildIndex();

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


// Get indexing statistics
        app.get("/index/status", ctx -> {

            try {
                IndexingService.IndexStatus status =
                        indexingService.getStatus();

                ctx.contentType("application/json");
                ctx.result(gson.toJson(status));

            } catch (Exception e) {

                ctx.status(500);
                ctx.contentType("application/json");

                ctx.result(gson.toJson(
                        new ErrorResponse(e.getMessage())
                ));
            }
        });

    }

    public record UpdateResponse(
            String book_id,
            String index
    ) {}

    public record ErrorResponse(String error) {}
}
