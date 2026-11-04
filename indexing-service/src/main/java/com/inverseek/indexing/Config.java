
package com.inverseek.indexing;

import java.nio.file.Path;

public record Config(
        int port,
        Path datalakePath,
        Path databasePath,
        int maxThreads
) {

    public static Config load() {
        return new Config(
                Integer.parseInt(System.getProperty("indexing.port", "7002")),
                Path.of(System.getProperty("datalake.path", "data/datalake")),
                Path.of(System.getProperty("datamarts.path", "data/datamarts.db")),
                Integer.parseInt(System.getProperty("server.max-threads", "0"))
        );
    }
}
