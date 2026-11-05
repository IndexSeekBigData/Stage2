package com.inverseek.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public record Config(int port, Path databasePath, int defaultLimit, int maxLimit,
                     List<String> allowedOrigins, int busyTimeoutMs, int maxThreads) {
    public Config {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid search.port");
        databasePath = databasePath.toAbsolutePath().normalize();
        if (maxLimit < 1 || maxLimit > 100 || defaultLimit < 1 || defaultLimit > maxLimit)
            throw new IllegalArgumentException("Search limits must satisfy 1 <= default <= max <= 100");
        if (busyTimeoutMs < 0) throw new IllegalArgumentException("SQLite busy timeout must be nonnegative");
        if (maxThreads < 0 || (maxThreads > 0 && maxThreads < 8))
            throw new IllegalArgumentException("server.max-threads must be 0 or at least 8");
        allowedOrigins = List.copyOf(allowedOrigins);
        if (allowedOrigins.isEmpty() || (allowedOrigins.contains("*") && allowedOrigins.size() != 1))
            throw new IllegalArgumentException("CORS must specify origins or a single *");
        for (String origin : allowedOrigins) {
            if (origin.equals("*")) continue;
            URI uri = URI.create(origin);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()))
                throw new IllegalArgumentException("CORS origins must be http(s)://host[:port] without a path");
        }
    }

    public static Config load() {
        return load(System.getenv(), System.getProperties());
    }

    static Config load(Map<String, String> env, Properties system) {
        Properties defaults = new Properties();
        try (var in = Config.class.getResourceAsStream("/application.properties")) {
            if (in != null) defaults.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load Search configuration", e);
        }
        return new Config(
                Integer.parseInt(value(env, system, defaults, "search.port", "7003")),
                Path.of(value(env, system, defaults, "datamarts.path", "../data/datamarts/datamarts.db")),
                Integer.parseInt(value(env, system, defaults, "search.default-limit", "20")),
                Integer.parseInt(value(env, system, defaults, "search.max-limit", "100")),
                Arrays.stream(value(env, system, defaults, "cors.allowed-origins", "*").split(","))
                        .map(String::strip).toList(),
                Integer.parseInt(value(env, system, defaults, "sqlite.busy-timeout-ms", "5000")),
                Integer.parseInt(value(env, system, defaults, "server.max-threads", "0")));
    }

    private static String value(Map<String, String> env, Properties system, Properties defaults,
                                String key, String fallback) {
        String value = env.get(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (value == null || value.isBlank()) value = system.getProperty(key);
        if (value == null || value.isBlank()) value = defaults.getProperty(key, fallback);
        return value.strip();
    }
}
