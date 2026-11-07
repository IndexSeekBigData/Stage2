package com.inverseek.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaLockTest {
    @TempDir Path root;

    private IngestionService service(BookSource source) {
        var lake = new Datalake(root, ZoneOffset.UTC, Clock.systemUTC());
        lake.recover();
        return new IngestionService(source, lake);
    }

    @Test void separateServiceInstancesSharingDatalakeDownloadOnlyOnce() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        BookSource source = id -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Source wait timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return TestFixtures.sampleBook();
        };
        var first = service(source);
        var second = service(source);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> first.ingest(1342));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var b = pool.submit(() -> second.ingest(1342));
            release.countDown();
            var firstResult = a.get(5, TimeUnit.SECONDS);
            var secondResult = b.get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertEquals(IngestionService.Outcome.DOWNLOADED, firstResult.outcome());
            assertEquals(IngestionService.Outcome.ALREADY_AVAILABLE, secondResult.outcome());
            assertEquals(firstResult.book(), secondResult.book());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void failedDownloadReleasesTheSharedLock() {
        var first = service(id -> { throw new BookNotFoundException("missing"); });
        assertThrows(BookNotFoundException.class, () -> first.ingest(11));
        var second = service(id -> TestFixtures.sampleBook());
        assertEquals(IngestionService.Outcome.DOWNLOADED, second.ingest(11).outcome());
        assertTrue(java.nio.file.Files.isRegularFile(root.resolve(".locks/11.lock")));
    }
}
