package com.inverseek.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.Properties;

/**
 * Service configuration.
 *
 * <p>Values come from {@code application.properties} on the classpath and can be overridden by
 * environment variables, so the same jar can run several replicas with different ports or data
 * paths. The environment variable name is the property name in upper case with dots replaced by
 * underscores, for example {@code ingestion.port} becomes {@code INGESTION_PORT}.
 */
public record Config(
        int port,
        Path datalakeRoot,
        String gutenbergBaseUrl,
        Duration downloadTimeout,
        long maxBookBytes,
        ZoneId partitionZone,
        int maxThreads) {

    public static Config load() {
        return load(System.getenv());
    }

    static Config load(Map<String, String> env) {
        Properties props = new Properties();
        try (InputStream in = Config.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read application.properties", e);
        }
        return new Config(
                Integer.parseInt(value(props, env, "ingestion.port", "7001")),
                Path.of(value(props, env, "datalake.path", "data/datalake")).toAbsolutePath().normalize(),
                stripTrailingSlash(value(props, env, "gutenberg.base-url", "https://www.gutenberg.org")),
                Duration.ofSeconds(Long.parseLong(value(props, env, "download.timeout-seconds", "60"))),
                Long.parseLong(value(props, env, "download.max-bytes", "52428800")),
                ZoneId.of(value(props, env, "datalake.partition-zone", "UTC")),
                Integer.parseInt(value(props, env, "server.max-threads", "0")));
    }

    private static String value(Properties props, Map<String, String> env, String key, String fallback) {
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String fromEnv = env.get(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return props.getProperty(key, fallback).trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
