package com.inverseek.control;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlStoreTest {
    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_000_000);

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        void advance(Duration by) { millis.addAndGet(by.toMillis()); }
    }

    private static final long LEASE = 1000;

    @TempDir Path root;
    TestClock clock;
    ControlStore store;

    @BeforeEach void setUp() throws Exception {
        clock = new TestClock();
        store = new ControlStore(root.resolve("sub/control.db"), clock);
    }

    @Test void createsDatabaseInWalMode() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("sub/control.db"));
             var s = c.createStatement(); var rs = s.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1));
        }
    }

    @Test void registerIsIdempotentAndKeepsState() throws Exception {
        assertEquals(new ControlStore.Registration(2, 0), store.register(List.of(1, 2)));
        var claim = store.claimDownload("w", LEASE).orElseThrow();
        assertEquals(new ControlStore.Registration(1, 2), store.register(List.of(1, 2, 3)));
        assertEquals(BookState.DOWNLOADING, store.find(claim.bookId()).orElseThrow().state());
    }

    @Test void claimsOldestFirstAndCountsAttempts() throws Exception {
        store.register(List.of(5));
        clock.advance(Duration.ofSeconds(1));
        store.register(List.of(3));
        var first = store.claimDownload("w", LEASE).orElseThrow();
        assertEquals(5, first.bookId());
        assertEquals(1, first.attempts());
        assertEquals(3, store.claimDownload("w", LEASE).orElseThrow().bookId());
        assertTrue(store.claimDownload("w", LEASE).isEmpty());
    }

    @Test void concurrentClaimsNeverReturnTheSameBook() throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (int id = 1; id <= 200; id++) ids.add(id);
        store.register(ids);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<List<Integer>>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                String owner = "w" + t;
                tasks.add(() -> {
                    List<Integer> claimed = new ArrayList<>();
                    while (true) {
                        var claim = store.claimDownload(owner, LEASE);
                        if (claim.isEmpty()) return claimed;
                        claimed.add(claim.get().bookId());
                    }
                });
            }
            List<Integer> all = new ArrayList<>();
            for (var result : pool.invokeAll(tasks)) all.addAll(result.get());
            assertEquals(200, all.size());
            assertEquals(200, new HashSet<>(all).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void staleWorkerCannotOverwriteTheNewOwner() throws Exception {
        store.register(List.of(1));
        var stale = store.claimDownload("stale", LEASE).orElseThrow();
        clock.advance(Duration.ofMillis(LEASE + 1));
        assertEquals(1, store.releaseExpiredLeases(3));
        var owner = store.claimDownload("owner", LEASE).orElseThrow();

        assertFalse(store.completeDownload(stale, "stale/path"));
        assertFalse(store.markFinal(stale, BookState.FAILED, "stale"));
        assertFalse(store.failAttempt(stale, "stale", 3, 0));
        assertFalse(store.releaseUnreachable(stale, "stale"));
        assertEquals("owner", store.find(1).orElseThrow().leaseOwner());
        assertEquals(BookState.DOWNLOADING, store.find(1).orElseThrow().state());

        assertTrue(store.completeDownload(owner, "good/path"));
        assertEquals("good/path", store.find(1).orElseThrow().datalakePath());
    }

    @Test void staleWorkerCannotAffectTheNextPhase() throws Exception {
        store.register(List.of(1));
        var download = store.claimDownload("w", LEASE).orElseThrow();
        assertTrue(store.completeDownload(download, "p"));
        assertFalse(store.completeDownload(download, "again"));
        assertEquals(BookState.DOWNLOADED, store.find(1).orElseThrow().state());
    }

    @Test void expiredLeasesReturnToWaitingOrFailWhenAttemptsAreExhausted() throws Exception {
        store.register(List.of(1, 2, 3));
        var ingesting = store.claimDownload("w", LEASE).orElseThrow();
        var downloaded = store.claimDownload("w", LEASE).orElseThrow();
        assertTrue(store.completeDownload(downloaded, "p"));
        var indexing = store.claimIndex("w", LEASE).orElseThrow();
        assertEquals(0, store.releaseExpiredLeases(3), "Live leases are kept");

        clock.advance(Duration.ofMillis(LEASE + 1));
        assertEquals(2, store.releaseExpiredLeases(3));
        assertEquals(BookState.PENDING, store.find(ingesting.bookId()).orElseThrow().state());
        assertEquals(BookState.DOWNLOADED, store.find(indexing.bookId()).orElseThrow().state());

        var last = store.claimDownload("w", LEASE).orElseThrow();
        clock.advance(Duration.ofMillis(LEASE + 1));
        assertEquals(1, store.releaseExpiredLeases(1));
        assertEquals(BookState.FAILED, store.find(last.bookId()).orElseThrow().state());
    }

    @Test void attemptsAreCountedPerPhase() throws Exception {
        store.register(List.of(1));
        var first = store.claimDownload("w", LEASE).orElseThrow();
        assertTrue(store.failAttempt(first, "e", 3, 0));
        var second = store.claimDownload("w", LEASE).orElseThrow();
        assertEquals(2, second.attempts());
        assertTrue(store.completeDownload(second, "p"));
        assertEquals(1, store.claimIndex("w", LEASE).orElseThrow().attempts());
    }

    @Test void failedAttemptWaitsWithGrowingDelay() throws Exception {
        store.register(List.of(1));
        assertTrue(store.failAttempt(store.claimDownload("w", LEASE).orElseThrow(), "e", 3, 1000));
        assertTrue(store.claimDownload("w", LEASE).isEmpty());
        clock.advance(Duration.ofMillis(1000));
        var second = store.claimDownload("w", LEASE).orElseThrow();
        assertTrue(store.failAttempt(second, "e", 3, 1000));
        clock.advance(Duration.ofMillis(1999));
        assertTrue(store.claimDownload("w", LEASE).isEmpty());
        clock.advance(Duration.ofMillis(1));
        assertTrue(store.claimDownload("w", LEASE).isPresent());
    }

    @Test void unreachableGivesTheAttemptBack() throws Exception {
        store.register(List.of(1));
        var claim = store.claimDownload("w", LEASE).orElseThrow();
        assertTrue(store.releaseUnreachable(claim, "down"));
        var book = store.find(1).orElseThrow();
        assertEquals(BookState.PENDING, book.state());
        assertEquals(0, book.attempts());
        assertEquals("down", book.lastError());
    }

    @Test void retryRules() throws Exception {
        assertEquals(ControlStore.RetryResult.UNKNOWN, store.retry(99));

        store.register(List.of(1));
        assertEquals(ControlStore.RetryResult.NOT_RETRYABLE, store.retry(1));

        var claim = store.claimDownload("w", LEASE).orElseThrow();
        assertEquals(ControlStore.RetryResult.NOT_RETRYABLE, store.retry(1));
        assertTrue(store.markFinal(claim, BookState.NOT_FOUND, "missing"));
        assertEquals(ControlStore.RetryResult.RETRIED, store.retry(1));
        assertEquals(BookState.PENDING, store.find(1).orElseThrow().state());

        var again = store.claimDownload("w", LEASE).orElseThrow();
        assertTrue(store.completeDownload(again, "p"));
        assertTrue(store.completeIndex(store.claimIndex("w", LEASE).orElseThrow()));
        assertEquals(ControlStore.RetryResult.RETRIED, store.retry(1));
        var book = store.find(1).orElseThrow();
        assertEquals(BookState.DOWNLOADED, book.state());
        assertEquals("p", book.datalakePath());
    }

    @Test void listFiltersAndPaginates() throws Exception {
        store.register(List.of(3, 1, 2));
        store.claimDownload("w", LEASE).orElseThrow();
        assertEquals(3, store.list(null, 0, 10).total());
        var pending = store.list(BookState.PENDING, 0, 1);
        assertEquals(2, pending.total());
        assertEquals(1, pending.books().size());
        assertEquals(1, store.list(null, 0, 1).books().get(0).bookId());
        assertEquals(0, store.list(null, 10, 5).books().size());
        assertEquals(1, store.activeLeases());
    }
}
