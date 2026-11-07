package com.inverseek.control;

import io.javalin.Javalin;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.sql.SQLException;
import java.time.Clock;

public final class App {
    private App() {}

    public static void main(String[] args) throws SQLException {
        Config config = Config.load();
        Running running = create(config);
        Runtime.getRuntime().addShutdownHook(new Thread(running::stop));
        running.start(config.port());
    }

    /** The HTTP server and the workers, which stop together. */
    public record Running(Javalin app, Orchestrator orchestrator) {
        public void start(int port) throws SQLException {
            app.start(port);
            orchestrator.start();
        }

        public void stop() {
            orchestrator.stop();
            app.stop();
        }
    }

    public static Running create(Config config) throws SQLException {
        ControlStore store = new ControlStore(config.databasePath(), Clock.systemUTC());
        HttpPipeline pipeline = new HttpPipeline(config);
        Javalin app = Javalin.create(javalin -> {
            javalin.http.defaultContentType = "application/json";
            javalin.showJavalinBanner = false;
            if (config.maxThreads() > 0) javalin.jetty.threadPool = new QueuedThreadPool(config.maxThreads());
        });
        new ControlApi(store, config, pipeline::dependencies).register(app);
        return new Running(app, new Orchestrator(config, store, pipeline));
    }
}
