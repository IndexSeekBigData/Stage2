
package com.inverseek.indexing;

import com.google.gson.Gson;
import io.javalin.Javalin;

public class App {

    public static void main(String[] args) throws Exception {

        Config config = Config.load();
        Gson gson = new Gson();

        IndexingService indexingService =
                new IndexingService(config);

        Javalin app = Javalin.create();

        app.get("/health", ctx -> {
            ctx.contentType("application/json");
            ctx.result(gson.toJson(new HealthResponse("indexing", "UP")));
        });

        IndexingApi.register(app, indexingService);

        app.start(config.port());
    }

    public record HealthResponse(String service, String status) {
    }
}
