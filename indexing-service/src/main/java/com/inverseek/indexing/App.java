package com.inverseek.indexing;

import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        Config config=Config.load();
        create(config).start(config.port());
    }

    public static Javalin create(Config config) throws Exception {
        IndexingService service=new IndexingService(config);
        Javalin app=Javalin.create(j -> {
            j.http.defaultContentType="application/json";
            if (config.maxThreads() > 0) j.jetty.threadPool=new QueuedThreadPool(config.maxThreads());
        });
        var gson=new GsonBuilder().serializeNulls().create();
        app.get("/health", ctx -> {
            var state=service.getStatus();
            ctx.result(gson.toJson(new HealthResponse("indexing","UP",state.index_version(),state.books_indexed(),
                    ((QueuedThreadPool)app.jettyServer().server().getThreadPool()).getMaxThreads())));
        });
        IndexingApi.register(app,service);
        return app;
    }

    public record HealthResponse(String service,String status,long index_version,int books_indexed,int max_threads) {}
}
