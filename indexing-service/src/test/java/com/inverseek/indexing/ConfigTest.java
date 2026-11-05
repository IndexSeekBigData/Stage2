package com.inverseek.indexing;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @Test void supportsEnvironmentAndLegacySystemProperties() {
        var system=new Properties();
        system.setProperty("indexing.port","7004");
        system.setProperty("datamarts.path","legacy.db");
        var config=Config.load(Map.of("INDEXING_PORT","7005","DATALAKE_PATH","lake"),system);
        assertEquals(7005,config.port());
        assertEquals(java.nio.file.Path.of("lake").toAbsolutePath().normalize(),config.datalakePath());
        assertEquals(java.nio.file.Path.of("legacy.db").toAbsolutePath().normalize(),config.databasePath());
    }

    @Test void rejectsInvalidPortsAndThreadCounts() {
        assertThrows(IllegalArgumentException.class,()->Config.load(Map.of("INDEXING_PORT","99999"),new Properties()));
        assertThrows(IllegalArgumentException.class,()->Config.load(Map.of("SERVER_MAX_THREADS","-1"),new Properties()));
    }
}
