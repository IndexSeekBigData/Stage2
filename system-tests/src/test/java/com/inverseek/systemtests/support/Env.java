package com.inverseek.systemtests.support;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public record Env(Path root, Path data, String control, List<String> ingestion, String indexing, List<String> search) {
    public static Env load() throws Exception {
        Path root = Path.of(System.getProperty("repository.root")).toAbsolutePath().normalize();
        String value = System.getenv("DATA_DIR");
        if (value == null || value.isBlank()) throw new IllegalStateException("DATA_DIR must point to a disposable managed deployment");
        Path data = Path.of(value).toAbsolutePath().normalize();
        JsonObject registry = JsonParser.parseString(Files.readString(data.resolve("run-config.json")).replace("\ufeff", "")).getAsJsonObject();
        if (!Path.of(registry.get("data_dir").getAsString()).toAbsolutePath().normalize().equals(data)
                || !Path.of(registry.get("repository").getAsString()).toAbsolutePath().normalize().equals(root))
            throw new IllegalStateException("Deployment registry does not match this repository/data directory");
        JsonObject urls = registry.getAsJsonObject("endpoints");
        return new Env(root, data, variable("CONTROL_URL", urls.get("control").getAsString()),
                list(variable("INGESTION_URL", String.join(",", urls.getAsJsonArray("ingestion").asList().stream().map(e -> e.getAsString()).toList()))),
                variable("INDEXING_URL", urls.get("indexing").getAsString()),
                list(variable("SEARCH_URL", String.join(",", urls.getAsJsonArray("search").asList().stream().map(e -> e.getAsString()).toList()))));
    }
    private static String variable(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
    private static List<String> list(String urls) {
        return Arrays.stream(urls.split(",")).map(String::strip).map(v -> v.replaceAll("/+$", "")).toList();
    }
    public Path database() { return data.resolve("datamarts/datamarts.db"); }
    public Path lake() { return data.resolve("datalake"); }
    public void requireFaultOwnership() throws Exception {
        if (!"true".equals(System.getenv("SYSTEM_FAULT_TESTS"))) throw new IllegalStateException("Fault tests require explicit opt-in");
        JsonObject owner = JsonParser.parseString(Files.readString(data.resolve("validation-owner.json"))).getAsJsonObject();
        JsonObject registry = JsonParser.parseString(Files.readString(data.resolve("run-config.json")).replace("\ufeff", "")).getAsJsonObject();
        if (!owner.get("token").getAsString().equals(System.getenv("SYSTEM_VALIDATION_TOKEN"))
                || !owner.get("run_id").getAsString().equals(registry.get("run_id").getAsString()))
            throw new IllegalStateException("Fault test deployment ownership mismatch");
    }
}
