package com.inverseek.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Downloads a book, splits it and stores it in the datalake.
 *
 * <p>Ingestion is idempotent: if the book already has a completion marker it is returned as is,
 * without downloading it again. Two requests for the same book inside this process are serialised
 * by a striped lock, so only the first one downloads. Across replicas, exclusive ownership comes
 * from the Control Module lease; a duplicate after a lease expiry is still harmless because the
 * oldest marker wins when the datalake is read.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int LOCK_STRIPES = 64;

    public enum Outcome { DOWNLOADED, ALREADY_AVAILABLE }

    public record Result(Outcome outcome, BookRecord book, long elapsedMillis) {
    }

    private final BookSource source;
    private final Datalake datalake;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
    private final AtomicLong downloads = new AtomicLong();

    public IngestionService(BookSource source, Datalake datalake) {
        this.source = source;
        this.datalake = datalake;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public Result ingest(int bookId) {
        long start = System.nanoTime();
        ReentrantLock lock = locks[Math.floorMod(bookId, LOCK_STRIPES)];
        lock.lock();
        try {
            Optional<BookRecord> existing = datalake.find(bookId);
            if (existing.isPresent()) {
                return new Result(Outcome.ALREADY_AVAILABLE, existing.get(), elapsedMillis(start));
            }
            String raw = source.fetch(bookId);
            BookParser.SplitBook split = BookParser.split(bookId, raw);
            BookRecord stored = datalake.store(bookId, split.header(), split.body());
            downloads.incrementAndGet();
            long elapsed = elapsedMillis(start);
            log.info("Book {} stored at {} in {} ms", bookId, stored.path(), elapsed);
            return new Result(Outcome.DOWNLOADED, stored, elapsed);
        } finally {
            lock.unlock();
        }
    }

    public Optional<BookRecord> status(int bookId) {
        return datalake.find(bookId);
    }

    public List<Integer> list() {
        return datalake.listBookIds();
    }

    public long downloadsSinceStart() {
        return downloads.get();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
