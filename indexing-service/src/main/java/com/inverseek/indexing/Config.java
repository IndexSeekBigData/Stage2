package com.inverseek.indexing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public record Config(int port, Path datalakePath, Path databasePath, int maxThreads) {
    public static Config load() {
        return load(System.getenv(), System.getProperties());
    }

    static Config load(Map<String, String> env, Properties system) {
        Properties defaults = new Properties();
        try (var in = Config.class.getResourceAsStream("/application.properties")) {
            if (in != null) defaults.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Environment takes precedence; -D remains supported for existing launch commands.
        int port = Integer.parseInt(value(env, system, defaults, "indexing.port", "7002"));
        int threads = Integer.parseInt(value(env, system, defaults, "server.max-threads", "0"));
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid indexing.port");
        if (threads < 0 || (threads > 0 && threads < 8))
            throw new IllegalArgumentException("server.max-threads must be 0 or at least 8");
        return new Config(port,
                Path.of(value(env, system, defaults, "datalake.path", "../data/datalake")).toAbsolutePath().normalize(),
                Path.of(value(env, system, defaults, "datamarts.path", "../data/datamarts/datamarts.db")).toAbsolutePath().normalize(),
                threads);
    }

    private static String value(Map<String, String> env, Properties system, Properties defaults,
                                String key, String fallback) {
        String value = env.get(key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (value == null || value.isBlank()) value = system.getProperty(key);
        if (value == null || value.isBlank()) value = defaults.getProperty(key, fallback);
        return value.strip();
    }
}
