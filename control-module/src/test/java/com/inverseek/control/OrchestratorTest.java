package com.inverseek.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorTest {
    private static final Duration SECONDS_5 = Duration.ofSeconds(5);

    @TempDir Path root;
    FakeServices services;
    ControlStore store;
    Orchestrator orchestrator;

    @BeforeEach void setUp() throws Exception {
        services = new FakeServices();
        store = new ControlStore(root.resolve("control.db"), Clock.systemUTC());
    }

    @AfterEach void tearDown() {
        if (orchestrator != null) orchestrator.stop();
        services.close();
    }

    private void run(int workers, Duration timeout, Duration lease) throws SQLException {
        Config config = services.config(root.resolve("control.db"), workers, timeout, lease);
        orchestrator = new Orchestrator(config, store, new HttpPipeline(config));
        orchestrator.start();
    }

    private void awaitNoActive() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            long active = store.counts().entrySet().stream()
                    .filter(e -> e.getKey().isActive()).mapToLong(java.util.Map.Entry::getValue).sum();
            if (active == 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Books still active: " + store.counts());
    }

    private ControlStore.Book book(int id) throws SQLException {
        return store.find(id).orElseThrow();
    }

    @Test void happyPathReachesIndexedWithTimestampsAndPath() throws Exception {
        store.register(List.of(1342));
        run(2, SECONDS_5, SECONDS_5);
        awaitNoActive();

        ControlStore.Book book = book(1342);
        assertEquals(BookState.INDEXED, book.state());
        assertEquals("20261105/10/1342", book.datalakePath());
        assertNull(book.leaseOwner());
        assertNull(book.lastError());
        assertNotNull(book.downloadStartedAt());
        assertNotNull(book.downloadedAt());
        assertNotNull(book.indexStartedAt());
        assertNotNull(book.indexedAt());
        assertEquals(1, services.ingestCount(1342));
        assertEquals(1, services.indexCount(1342));
    }

    @Test void missingBookIsNotFoundAndNeverIndexed() throws Exception {
        services.ingest = (id, call) -> new FakeServices.Reply(404, "{\"error\":\"book_not_found\"}", 0);
        store.register(List.of(9999999));
        run(1, SECONDS_5, SECONDS_5);
        awaitNoActive();

        assertEquals(BookState.NOT_FOUND, book(9999999).state());
        assertTrue(book(9999999).lastError().contains("book_not_found"));
        assertEquals(1, services.ingestCount(9999999));
        assertEquals(0, services.indexCount(9999999));
    }

    @Test void invalidBookFormatIsNotFound() throws Exception {
        services.ingest = (id, call) -> new FakeServices.Reply(422, "{\"error\":\"invalid_book_format\"}", 0);
        store.register(List.of(55));
        run(1, SECONDS_5, SECONDS_5);
        awaitNoActive();

        assertEquals(BookState.NOT_FOUND, book(55).state());
    }

    @Test void rejectedRequestIsFailedWithoutRetry() throws Exception {
        services.ingest = (id, call) -> new FakeServices.Reply(400, "{\"error\":\"invalid_book_id\"}", 0);
        store.register(List.of(7));
        run(1, SECONDS_5, SECONDS_5);
        awaitNoActive();

        assertEquals(BookState.FAILED, book(7).state());
        assertEquals(1, services.ingestCount(7));
    }

    @Test void retriesThenSucceedsInEachPhase() throws Exception {
        FakeServices.Reply error = new FakeServices.Reply(502, "{\"error\":\"upstream_error\"}", 0);
        var goodIngest = services.ingest;
        var goodIndex = services.index;
        services.ingest = (id, call) -> call < 3 ? error : goodIngest.apply(id, call);
        services.index = (id, call) -> call < 3 ? new FakeServices.Reply(500, "{}", 0) : goodIndex.apply(id, call);
        store.register(List.of(11));
        run(1, SECONDS_5, SECONDS_5);
        awaitNoActive();

        assertEquals(BookState.INDEXED, book(11).state());
        assertEquals(3, services.ingestCount(11));
        assertEquals(3, services.indexCount(11));
        assertNull(book(11).lastError());
    }

    @Test void failsAfterMaxAttempts() throws Exception {
        services.ingest = (id, call) -> new FakeServices.Reply(504, "{\"error\":\"upstream_timeout\"}", 0);
        store.register(List.of(12));
        run(1, SECONDS_5, SECONDS_5);
        awaitNoActive();

        assertEquals(BookState.FAILED, book(12).state());
        assertEquals(3, services.ingestCount(12));
        assertTrue(book(12).lastError().contains("504"));
    }

    @Test void callTimeoutConsumesAttemptsUntilFailed() throws Exception {
        services.index = (id, call) -> new FakeServices.Reply(200, "{}", 1000);
        store.register(List.of(13));
        run(1, Duration.ofMillis(200), SECONDS_5);
        awaitNoActive();

        assertEquals(BookState.FAILED, book(13).state());
        assertEquals(3, services.indexCount(13));
    }

    @Test void unreachableServiceDoesNotConsumeAttempts() throws Exception {
        services.stopIndexing();
        store.register(List.of(14));
        run(1, SECONDS_5, SECONDS_5);

        long deadline = System.currentTimeMillis() + 10_000;
        while (book(14).lastError() == null || !book(14).lastError().contains("unreachable")) {
            assertTrue(System.currentTimeMillis() < deadline, "Book never reported the unreachable service");
            Thread.sleep(10);
        }
        Thread.sleep(500);
        ControlStore.Book book = book(14);
        assertTrue(book.state() == BookState.DOWNLOADED || book.state() == BookState.INDEXING, book.state().name());
        assertTrue(book.attempts() <= 1, "attempts=" + book.attempts());
    }

    @Test void eightWorkersProcessEveryBookExactlyOnce() throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (int id = 1; id <= 50; id++) ids.add(id);
        store.register(ids);
        run(8, SECONDS_5, SECONDS_5);
        awaitNoActive();

        assertEquals(50, store.counts().get(BookState.INDEXED));
        for (int id : ids) {
            assertEquals(1, services.ingestCount(id), "ingest calls of " + id);
            assertEquals(1, services.indexCount(id), "index calls of " + id);
        }
    }

    @Test void hungCallLosesItsLeaseAndTheBookIsRecovered() throws Exception {
        var goodIngest = services.ingest;
        // The first call outlives the lease, so a second attempt must finish the book.
        services.ingest = (id, call) -> call == 1
                ? new FakeServices.Reply(200, "{\"status\":\"downloaded\",\"path\":\"stale/path\"}", 1500)
                : goodIngest.apply(id, call);
        store.register(List.of(15));
        run(2, SECONDS_5, Duration.ofMillis(300));
        awaitNoActive();
        Thread.sleep(1600);

        ControlStore.Book book = book(15);
        assertEquals(BookState.INDEXED, book.state());
        assertEquals(2, services.ingestCount(15));
        assertEquals("20261105/10/15", book.datalakePath(), "The stale worker must not overwrite the result");
    }

    @Test void restartRecoversBooksLeftInProgress() throws Exception {
        store.register(List.of(16, 17));
        // A previous process died holding both books, with leases that have already expired.
        assertTrue(store.claimDownload("dead-process", -1000).isPresent());
        assertTrue(store.claimDownload("dead-process", -1000).isPresent());
        assertEquals(2, store.counts().get(BookState.DOWNLOADING));

        run(2, SECONDS_5, SECONDS_5);
        awaitNoActive();

        assertEquals(BookState.INDEXED, book(16).state());
        assertEquals(BookState.INDEXED, book(17).state());
    }
}
