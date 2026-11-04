package com.inverseek.ingestion;

import io.javalin.Javalin;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/** Entry point: {@code java -jar ingestion-service-1.0.0.jar}. */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private App() {
    }

    public static void main(String[] args) {
        Config config = Config.load();
        Javalin app = create(config, new HttpBookSource(config.gutenbergBaseUrl(), config.downloadTimeout(), config.maxBookBytes()));
        app.start(config.port());
        log.info("Ingestion Service listening on port {} with datalake {}", config.port(), config.datalakeRoot());
    }

    /** Builds the service without starting it, so tests can start it on a free port with a fake source. */
    public static Javalin create(Config config, BookSource source) {
        Datalake datalake = new Datalake(config.datalakeRoot(), config.partitionZone(), Clock.systemUTC());
        datalake.recover();
        IngestionService service = new IngestionService(source, datalake);

        Javalin app = Javalin.create(javalin -> {
            javalin.http.defaultContentType = "application/json";
            javalin.showJavalinBanner = false;
            if (config.maxThreads() > 0) {
                javalin.jetty.threadPool = new QueuedThreadPool(config.maxThreads());
            }
        });
        new IngestionApi(service).register(app);
        return app;
    }
}
