package com.inverseek.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatalakeTest {

    private static final Instant NOW = Instant.parse("2026-11-04T14:30:00Z");

    @TempDir
    Path root;

    private Datalake datalake(Instant now) {
        return new Datalake(root, ZoneId.of("UTC"), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void storesTheStageOneLayoutWithMarker() throws IOException {
        BookRecord record = datalake(NOW).store(1342, "header\n", "body\n");

        Path partition = root.resolve("20261104/14");
        assertEquals("header\n", Files.readString(partition.resolve("1342.header.txt")));
        assertEquals("body\n", Files.readString(partition.resolve("1342.body.txt")));
        assertTrue(Files.exists(partition.resolve("1342.success.json")));
        assertEquals("20261104/14/1342", record.path());
        assertEquals("20261104/14/1342.body.txt", record.bodyPath());
        assertEquals(5, record.bodyBytes());
        assertEquals(Datalake.sha256("body\n".getBytes(StandardCharsets.UTF_8)), record.bodySha256());
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        datalake(NOW).store(1, "h\n", "b\n");
        try (Stream<Path> tmp = Files.list(root.resolve(".tmp"))) {
            assertEquals(0, tmp.count());
        }
    }

    @Test
    void recoverRebuildsTheCatalogFromMarkers() {
        datalake(NOW).store(5, "h\n", "b\n");
        datalake(NOW).store(2, "h\n", "b\n");

        Datalake restarted = datalake(NOW);
        restarted.recover();

        assertEquals(List.of(2, 5), restarted.listBookIds());
        assertTrue(restarted.find(5).isPresent());
        assertFalse(restarted.find(99).isPresent());
    }

    @Test
    void findSeesBooksWrittenByAnotherReplica() {
        Datalake replicaA = datalake(NOW);
        Datalake replicaB = datalake(NOW);
        replicaA.store(77, "h\n", "b\n");

        assertTrue(replicaB.find(77).isPresent());
    }

    @Test
    void filesWithoutMarkerAreInvisible() throws IOException {
        Path partition = Files.createDirectories(root.resolve("20261104/14"));
        Files.writeString(partition.resolve("9.header.txt"), "h");
        Files.writeString(partition.resolve("9.body.txt"), "partial");

        Datalake lake = datalake(NOW);
        lake.recover();

        assertFalse(lake.find(9).isPresent());
    }

    @Test
    void recoverDeletesOldLeftoversButKeepsRecentOnes() throws IOException {
        Path partition = Files.createDirectories(root.resolve("20261104/14"));
        Path oldOrphan = Files.writeString(partition.resolve("9.body.txt"), "partial");
        Path recentOrphan = Files.writeString(partition.resolve("10.body.txt"), "partial");
        Path oldTmp = Files.createDirectories(root.resolve(".tmp/9-abc"));
        Files.setLastModifiedTime(oldOrphan, FileTime.from(NOW.minusSeconds(3600)));
        Files.setLastModifiedTime(oldTmp, FileTime.from(NOW.minusSeconds(3600)));
        Files.setLastModifiedTime(recentOrphan, FileTime.from(NOW.minusSeconds(60)));

        datalake(NOW).recover();

        assertFalse(Files.exists(oldOrphan));
        assertFalse(Files.exists(oldTmp));
        assertTrue(Files.exists(recentOrphan));
    }

    @Test
    void whenABookHasTwoMarkersTheOldestWins() {
        datalake(NOW).store(3, "first\n", "first\n");
        datalake(NOW.plusSeconds(7200)).store(3, "second\n", "second\n");

        Datalake restarted = datalake(NOW);
        restarted.recover();

        assertEquals("20261104/14/3", restarted.find(3).orElseThrow().path());
    }
}
