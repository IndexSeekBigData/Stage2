package com.inverseek.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Coordination cost per phase over the INDEXED books, in milliseconds. */
final class Metrics {
    private Metrics() {}

    static Map<String, Object> from(List<ControlStore.Book> books) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("books_indexed", books.size());
        body.put("queue_wait_ms", phase(books, b -> diff(b.queuedAt(), b.downloadStartedAt())));
        body.put("ingest_ms", phase(books, b -> diff(b.downloadStartedAt(), b.downloadedAt())));
        body.put("index_wait_ms", phase(books, b -> diff(b.downloadedAt(), b.indexStartedAt())));
        body.put("index_ms", phase(books, b -> diff(b.indexStartedAt(), b.indexedAt())));
        body.put("end_to_end_ms", phase(books, b -> diff(b.queuedAt(), b.indexedAt())));
        body.put("books_per_second", throughput(books));
        return body;
    }

    private static Long diff(Long from, Long to) {
        return from == null || to == null ? null : to - from;
    }

    private static Map<String, Object> phase(List<ControlStore.Book> books, Function<ControlStore.Book, Long> value) {
        List<Long> values = new ArrayList<>();
        for (ControlStore.Book book : books) {
            Long v = value.apply(book);
            if (v != null) values.add(v);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (values.isEmpty()) {
            result.put("avg", null);
            result.put("p95", null);
            return result;
        }
        Collections.sort(values);
        double avg = values.stream().mapToLong(Long::longValue).average().orElse(0);
        int rank = (int) Math.ceil(0.95 * values.size()) - 1;
        result.put("avg", Math.round(avg * 10) / 10.0);
        result.put("p95", values.get(rank));
        return result;
    }

    private static Double throughput(List<ControlStore.Book> books) {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (ControlStore.Book book : books) {
            if (book.indexedAt() == null) continue;
            first = Math.min(first, book.queuedAt());
            last = Math.max(last, book.indexedAt());
        }
        if (last <= first) return null;
        return Math.round(books.size() * 1000.0 / (last - first) * 100) / 100.0;
    }
}
