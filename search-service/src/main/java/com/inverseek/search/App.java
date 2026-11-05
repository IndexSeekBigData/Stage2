package com.inverseek.search;

import io.javalin.Javalin;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public final class App {
    private App() {}

    public static void main(String[] args) {
        Config config = Config.load();
        create(config).start(config.port());
    }

    public static Javalin create(Config config) {
        Javalin app = Javalin.create(javalin -> {
            javalin.http.defaultContentType = "application/json";
            javalin.showJavalinBanner = false;
            if (config.maxThreads() > 0)
                javalin.jetty.threadPool = new QueuedThreadPool(config.maxThreads());
            javalin.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                rule.allowCredentials = false;
                if (config.allowedOrigins().contains("*")) rule.anyHost();
                else {
                    for (String origin : config.allowedOrigins()) rule.allowHost(origin);
                }
            }));
        });
        // No database is opened at startup: an absent index is a recoverable HTTP 503.
        new SearchApi(config, new SearchService(new IndexReader(config))).register(app);
        return app;
    }
}
