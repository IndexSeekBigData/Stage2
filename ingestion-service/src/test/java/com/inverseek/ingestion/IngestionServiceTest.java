package com.inverseek.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IngestionServiceTest {

    @TempDir
    Path root;

    /** Fake Gutenberg that counts downloads and is slow enough to expose races. */
    static final class CountingSource implements BookSource {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String fetch(int bookId) {
            calls.incrementAndGet();
            if (bookId == 404) {
                throw new BookNotFoundException("missing");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TestFixtures.sampleBook();
        }
    }

    private IngestionService service(CountingSource source) {
        Datalake datalake = new Datalake(root, ZoneId.of("UTC"), Clock.systemUTC());
        datalake.recover();
        return new IngestionService(source, datalake);
    }

    @Test
    void secondIngestionOfTheSameBookDoesNotDownloadAgain() {
        CountingSource source = new CountingSource();
        IngestionService service = service(source);

        IngestionService.Result first = service.ingest(1342);
        IngestionService.Result second = service.ingest(1342);

        assertEquals(IngestionService.Outcome.DOWNLOADED, first.outcome());
        assertEquals(IngestionService.Outcome.ALREADY_AVAILABLE, second.outcome());
        assertEquals(first.book(), second.book());
        assertEquals(1, source.calls.get());
    }

    @Test
    void concurrentRequestsForTheSameBookDownloadItOnce() throws Exception {
        CountingSource source = new CountingSource();
        IngestionService service = service(source);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestionService.Result>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.ingest(1342);
            }));
        }
        start.countDown();

        int downloaded = 0;
        for (Future<IngestionService.Result> f : results) {
            if (f.get().outcome() == IngestionService.Outcome.DOWNLOADED) {
                downloaded++;
            }
        }
        pool.shutdown();

        assertEquals(1, downloaded);
        assertEquals(1, source.calls.get());
        assertEquals(List.of(1342), service.list());
    }

    @Test
    void differentBooksCanBeIngestedInParallel() throws Exception {
        CountingSource source = new CountingSource();
        IngestionService service = service(source);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<IngestionService.Result>> results = new ArrayList<>();
        for (int id = 1; id <= 20; id++) {
            int bookId = id;
            results.add(pool.submit(() -> service.ingest(bookId)));
        }
        for (Future<IngestionService.Result> f : results) {
            f.get();
        }
        pool.shutdown();

        assertEquals(20, service.list().size());
        assertEquals(20, source.calls.get());
    }

    @Test
    void missingBookIsNotStored() {
        IngestionService service = service(new CountingSource());

        assertThrows(BookNotFoundException.class, () -> service.ingest(404));
        assertEquals(List.of(), service.list());
    }
}
