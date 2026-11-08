package com.inverseek.systemtests.support;

import java.time.Duration;

public final class Await {
    private Await() {}
    @FunctionalInterface public interface Condition { boolean evaluate() throws Exception; }
    public static void until(String description, Duration timeout, Condition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable last = null;
        do {
            try { if (condition.evaluate()) return; } catch (Exception e) { last = e; }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException(description);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            Thread.sleep(Math.min(100, Math.max(1, remaining / 1_000_000)));
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out: " + description, last);
    }
}
