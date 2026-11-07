package com.inverseek.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public record Config(int port, Path databasePath, String ingestionUrl, String indexingUrl,
                     int workers, int maxAttempts,
                     Duration ingestionTimeout, Duration indexingTimeout,
                     Duration ingestLease, Duration indexLease,
                     Duration retryDelay, Duration reaperInterval, Duration idleWait, Duration maxBackoff,
                     int maxThreads) {

    public Config {
        databasePath = databasePath.toAbsolutePath().normalize();
        ingestionUrl = String.join(",", Arrays.stream(ingestionUrl.split(",", -1)).map(Config::endpoint).toList());
        indexingUrl = endpoint(indexingUrl);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid control.port");
        for (Duration duration : List.of(ingestionTimeout, indexingTimeout, ingestLease, indexLease,
                reaperInterval, idleWait, maxBackoff)) {
            if (duration.isNegative() || duration.isZero())
                throw new IllegalArgumentException("Timeouts, leases and polling waits must be positive");
        }
        if (retryDelay.isNegative()) throw new IllegalArgumentException("Retry delay must be nonnegative");
        if (workers < 1) throw new IllegalArgumentException("control.workers must be at least 1");
        if (maxAttempts < 1) throw new IllegalArgumentException("control.max-attempts must be at least 1");
        if (maxThreads < 0 || (maxThreads > 0 && maxThreads < 8))
            throw new IllegalArgumentException("server.max-threads must be 0 or at least 8");
    }

    public static Config load() {
        return load(System.getenv());
    }

    static Config load(Map<String, String> env) {
        Properties defaults = new Properties();
        try (var in = Config.class.getResourceAsStream("/application.properties")) {
            if (in != null) defaults.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load Control configuration", e);
        }
        Config config = new Config(
                Integer.parseInt(value(env, defaults, "control.port", "7000")),
                Path.of(value(env, defaults, "control.db.path", "../data/control/control.db")),
                value(env, defaults, "ingestion.url", "http://localhost:7001"),
                value(env, defaults, "indexing.url", "http://localhost:7002"),
                Integer.parseInt(value(env, defaults, "control.workers", "4")),
                Integer.parseInt(value(env, defaults, "control.max-attempts", "3")),
                seconds(env, defaults, "ingestion.timeout-seconds", "90"),
                seconds(env, defaults, "indexing.timeout-seconds", "120"),
                seconds(env, defaults, "control.lease.ingest-seconds", "120"),
                seconds(env, defaults, "control.lease.index-seconds", "180"),
                seconds(env, defaults, "control.retry-delay-seconds", "2"),
                seconds(env, defaults, "control.reaper-seconds", "10"),
                Duration.ofMillis(Long.parseLong(value(env, defaults, "control.idle-millis", "500"))),
                seconds(env, defaults, "control.max-backoff-seconds", "10"),
                Integer.parseInt(value(env, defaults, "server.max-threads", "0")));
        if (config.ingestLease().compareTo(config.ingestionTimeout()) <= 0
                || config.indexLease().compareTo(config.indexingTimeout()) <= 0)
            throw new IllegalArgumentException("Each lease must exceed its call timeout");
        return config;
    }

    private static Duration seconds(Map<String, String> env, Properties defaults, String key, String fallback) {
        return Duration.ofSeconds(Long.parseLong(value(env, defaults, key, fallback)));
    }

    private static String value(Map<String, String> env, Properties defaults, String key, String fallback) {
        String value = env.get(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (value == null || value.isBlank()) value = defaults.getProperty(key, fallback);
        return value.strip();
    }

    public List<String> ingestionUrls() {
        return List.of(ingestionUrl.split(","));
    }

    private static String endpoint(String value) {
        String url = value.strip().replaceAll("/+$", "");
        URI uri = URI.create(url);
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null
                || uri.getPort() > 65535)
            throw new IllegalArgumentException("Service URLs must be absolute http(s) endpoints: " + value);
        return url;
    }
}
