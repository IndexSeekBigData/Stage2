package com.inverseek.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Worker threads that move books through the pipeline, and the reaper that releases expired leases. */
public final class Orchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(Orchestrator.class);

    private final Config config;
    private final ControlStore store;
    private final Pipeline pipeline;
    private final String processId = UUID.randomUUID().toString();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public Orchestrator(Config config, ControlStore store, Pipeline pipeline) {
        this.config = config;
        this.store = store;
        this.pipeline = pipeline;
    }

    public synchronized void start() throws SQLException {
        if (running) return;
        running = true;
        int recovered = store.releaseExpiredLeases(config.maxAttempts());
        if (recovered > 0) LOG.info("Recovered {} books with expired leases", recovered);
        for (int i = 0; i < config.workers(); i++) {
            String owner = processId + "-worker-" + i;
            Thread worker = new Thread(() -> workLoop(owner), "control-worker-" + i);
            threads.add(worker);
        }
        threads.add(new Thread(this::reapLoop, "control-reaper"));
        threads.forEach(Thread::start);
    }

    public synchronized void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    private void workLoop(String owner) {
        long backoff = config.idleWait().toMillis();
        while (running) {
            try {
                Optional<ControlStore.Claim> claim = claimNext(owner);
                if (claim.isEmpty()) {
                    backoff = config.idleWait().toMillis();
                    pause(backoff);
                    continue;
                }
                if (process(claim.get())) {
                    backoff = config.idleWait().toMillis();
                } else {
                    // A service is down: wait instead of spinning on the same work.
                    pause(backoff);
                    backoff = Math.min(backoff * 2, config.maxBackoff().toMillis());
                }
            } catch (SQLException | RuntimeException e) {
                LOG.error("Worker {} failed; continuing", owner, e);
                pause(config.idleWait().toMillis());
            }
        }
    }

    /** Books that are already downloaded go first, so work in progress finishes before new work starts. */
    private Optional<ControlStore.Claim> claimNext(String owner) throws SQLException {
        Optional<ControlStore.Claim> claim = store.claimIndex(owner, config.indexLease().toMillis());
        return claim.isPresent() ? claim : store.claimDownload(owner, config.ingestLease().toMillis());
    }

    /** Returns false when the service could not be reached. */
    private boolean process(ControlStore.Claim claim) throws SQLException {
        boolean download = claim.state() == BookState.DOWNLOADING;
        Pipeline.Outcome outcome = download ? pipeline.ingest(claim.bookId()) : pipeline.index(claim.bookId());
        boolean applied = switch (outcome.kind()) {
            case SUCCESS -> download ? store.completeDownload(claim, outcome.detail()) : store.completeIndex(claim);
            case NOT_FOUND -> store.markFinal(claim, BookState.NOT_FOUND, outcome.detail());
            case REJECTED -> store.markFinal(claim, BookState.FAILED, outcome.detail());
            case RETRYABLE -> store.failAttempt(claim, outcome.detail(), config.maxAttempts(), config.retryDelay().toMillis());
            case UNREACHABLE -> store.releaseUnreachable(claim, outcome.detail());
        };
        if (!applied) {
            // Safe to discard: ingestion and indexing are idempotent, and the new owner decides the state.
            LOG.warn("Book {}: {} lost its lease, discarding the result of {}", claim.bookId(), claim.owner(), claim.state());
        }
        return outcome.kind() != Pipeline.Kind.UNREACHABLE;
    }

    private void reapLoop() {
        while (running) {
            pause(config.reaperInterval().toMillis());
            if (!running) return;
            try {
                int released = store.releaseExpiredLeases(config.maxAttempts());
                if (released > 0) LOG.warn("Released {} expired leases", released);
            } catch (SQLException | RuntimeException e) {
                LOG.error("Lease reaper failed; continuing", e);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
