package com.inverseek.search;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SearchService {
    private final IndexReader reader;

    public SearchService(IndexReader reader) {
        this.reader = reader;
    }

    public Response search(SearchQuery query) throws SQLException {
        try (var snapshot = reader.openSnapshot()) {
            Map<String, Long> frequencies = snapshot.documentFrequencies(query.terms());
            for (String term : query.terms()) {
                if (frequencies.getOrDefault(term, 0L) == 0)
                    return response(query, snapshot.state().version(), new IndexReader.Page(0, List.of()));
            }
            Map<String, Double> idfs = new HashMap<>();
            for (String term : query.terms()) {
                idfs.put(term, Math.log1p((double) snapshot.state().booksIndexed() / frequencies.get(term)));
            }
            return response(query, snapshot.state().version(), snapshot.search(query, idfs));
        }
    }

    private static Response response(SearchQuery query, long version, IndexReader.Page page) {
        return new Response(query.query(), query.filters(), page.total(), query.offset(), query.limit(),
                version, page.results());
    }

    public Optional<IndexReader.Book> book(int id) throws SQLException {
        try (var snapshot = reader.openSnapshot()) {
            return snapshot.book(id);
        }
    }

    public IndexReader.Filters filters() throws SQLException {
        try (var snapshot = reader.openSnapshot()) {
            return snapshot.filters();
        }
    }

    public IndexReader.State health() throws SQLException {
        try (var snapshot = reader.openSnapshot()) {
            return snapshot.state();
        }
    }

    public record Response(String query, Map<String, Object> filters, long total, int offset, int limit,
                           long index_version, List<IndexReader.Result> results) {}
}
