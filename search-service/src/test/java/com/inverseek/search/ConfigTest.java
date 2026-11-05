package com.inverseek.search;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @Test void environmentOverridesSystemPropertiesAndDefaults() {
        var system = new Properties();
        system.setProperty("search.port", "7009");
        system.setProperty("datamarts.path", "legacy.db");
        var config = Config.load(Map.of("SEARCH_PORT", "7010", "SEARCH_DEFAULT_LIMIT", "5",
                "CORS_ALLOWED_ORIGINS", "http://localhost:5173,https://example.com"), system);
        assertEquals(7010, config.port());
        assertEquals(Path.of("legacy.db").toAbsolutePath().normalize(), config.databasePath());
        assertEquals(5, config.defaultLimit());
        assertEquals(List.of("http://localhost:5173", "https://example.com"), config.allowedOrigins());
    }

    @Test void rejectsInvalidLimitsOriginsAndThreadCounts() {
        for (var env : List.of(Map.of("SEARCH_MAX_LIMIT", "101"), Map.of("SEARCH_DEFAULT_LIMIT", "0"),
                Map.of("SEARCH_MAX_LIMIT", "10"), Map.of("SEARCH_PORT", "99999"),
                Map.of("SQLITE_BUSY_TIMEOUT_MS", "-1"), Map.of("SERVER_MAX_THREADS", "1"),
                Map.of("CORS_ALLOWED_ORIGINS", "*,http://localhost:5173"),
                Map.of("CORS_ALLOWED_ORIGINS", "https://example.com/path"))) {
            assertThrows(IllegalArgumentException.class, () -> Config.load(env, new Properties()));
        }
    }
}
