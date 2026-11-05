package com.inverseek.search;

import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class IndexReader {
    private final Config config;

    public IndexReader(Config config) {
        this.config = config;
    }

    public Snapshot openSnapshot() {
        if (!Files.isRegularFile(config.databasePath())) {
            throw new IndexUnavailableException("index_not_ready", "The published index does not exist yet");
        }
        Connection connection = null;
        try {
            SQLiteConfig sqlite = new SQLiteConfig();
            sqlite.setReadOnly(true); // Removes CREATE/READWRITE flags before opening the file.
            sqlite.setBusyTimeout(config.busyTimeoutMs());
            connection = sqlite.createConnection("jdbc:sqlite:" + config.databasePath());
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
            }
            connection.setAutoCommit(false);
            validateSchema(connection);
            State state;
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT version,books_indexed FROM index_state WHERE id=1")) {
                if (!rs.next() || rs.getLong(1) < 0 || rs.getLong(2) < 0)
                    throw new IndexUnavailableException("index_not_ready", "The index state is not initialized");
                state = new State(rs.getLong(1), rs.getLong(2));
            }
            return new Snapshot(connection, state);
        } catch (SQLException | RuntimeException e) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException closeError) { e.addSuppressed(closeError); }
            }
            if (e instanceof IndexUnavailableException unavailable) throw unavailable;
            throw new IndexUnavailableException("index_unavailable", "Cannot read the published index", e);
        }
    }

    private static void validateSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA user_version")) {
            if (!rs.next() || rs.getInt(1) != 1)
                throw new IndexUnavailableException("index_incompatible", "Search requires datamart schema version 1");
        }
        Map<String, Set<String>> required = Map.of(
                "books", Set.of("book_id", "title", "author", "author_norm", "language", "year", "word_count", "indexed_at"),
                "postings", Set.of("term", "book_id", "tf"),
                "index_state", Set.of("id", "version", "books_indexed", "last_update"));
        for (var table : required.entrySet()) {
            Set<String> columns = new HashSet<>();
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("PRAGMA table_info(" + table.getKey() + ")")) {
                while (rs.next()) columns.add(rs.getString("name"));
            }
            if (!columns.containsAll(table.getValue()))
                throw new IndexUnavailableException("index_incompatible", "Missing required columns in " + table.getKey());
        }
    }

    public static final class Snapshot implements AutoCloseable {
        private final Connection connection;
        private final State state;

        private Snapshot(Connection connection, State state) {
            this.connection = connection;
            this.state = state;
        }

        public State state() {
            return state;
        }

        public Map<String, Long> documentFrequencies(List<String> terms) throws SQLException {
            Map<String, Long> result = new HashMap<>();
            String sql = "SELECT term,COUNT(*) FROM postings WHERE term IN (" + placeholders(terms.size()) + ") GROUP BY term";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, new ArrayList<>(terms));
                try (var rs = statement.executeQuery()) {
                    while (rs.next()) result.put(rs.getString(1), rs.getLong(2));
                }
            }
            return result;
        }

        public Page search(SearchQuery query, Map<String, Double> idfs) throws SQLException {
            List<Object> bindings = new ArrayList<>();
            StringBuilder expression = new StringBuilder("CASE p.term");
            for (String term : query.terms()) {
                expression.append(" WHEN ? THEN ?");
                bindings.add(term);
                bindings.add(idfs.get(term));
            }
            expression.append(" ELSE 0 END");
            StringBuilder cte = new StringBuilder("""
                    WITH matches AS (
                        SELECT p.book_id,SUM(p.tf * 
                    """).append(expression).append("""
                        ) AS score
                        FROM postings p JOIN books b ON b.book_id=p.book_id
                        WHERE p.term IN (
                    """).append(placeholders(query.terms().size())).append(")");
            bindings.addAll(query.terms());
            if (query.authorNorm() != null) {
                cte.append(" AND b.author_norm LIKE '%' || ? || '%' ESCAPE '!'");
                bindings.add(escapeLike(query.authorNorm()));
            }
            if (query.language() != null) {
                cte.append(" AND b.language=?");
                bindings.add(query.language());
            }
            if (query.year() != null) {
                cte.append(" AND b.year=?");
                bindings.add(query.year());
            }
            cte.append(" GROUP BY p.book_id HAVING COUNT(*)=?) ");
            bindings.add(query.terms().size());

            long total;
            try (PreparedStatement statement = connection.prepareStatement(cte + "SELECT COUNT(*) FROM matches")) {
                bind(statement, bindings);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            if (query.offset() >= total) return new Page(total, List.of());
            List<Result> results = new ArrayList<>();
            String sql = cte + """
                    SELECT b.book_id,b.title,b.author,b.language,b.year,m.score
                    FROM matches m JOIN books b ON b.book_id=m.book_id
                    ORDER BY m.score DESC,CAST(m.book_id AS INTEGER) ASC
                    LIMIT ? OFFSET ?
                    """;
            List<Object> pageBindings = new ArrayList<>(bindings);
            pageBindings.add(query.limit());
            pageBindings.add(query.offset());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, pageBindings);
                try (var rs = statement.executeQuery()) {
                    while (rs.next()) {
                        results.add(new Result(rs.getInt("book_id"), rs.getString("title"), rs.getString("author"),
                                rs.getString("language"), nullableInt(rs, "year"), rs.getDouble("score")));
                    }
                }
            }
            return new Page(total, List.copyOf(results));
        }

        public Optional<Book> book(int id) throws SQLException {
            try (var statement = connection.prepareStatement("""
                    SELECT book_id,title,author,language,year,word_count,indexed_at FROM books WHERE book_id=?
                    """)) {
                statement.setString(1, Integer.toString(id));
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new Book(rs.getInt("book_id"), rs.getString("title"), rs.getString("author"),
                            rs.getString("language"), nullableInt(rs, "year"), rs.getLong("word_count"),
                            rs.getString("indexed_at")));
                }
            }
        }

        public Filters filters() throws SQLException {
            List<Language> languages = new ArrayList<>();
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("""
                         SELECT language,COUNT(*) AS books FROM books WHERE language IS NOT NULL
                         GROUP BY language ORDER BY language ASC
                         """)) {
                while (rs.next()) languages.add(new Language(rs.getString(1), rs.getLong(2)));
            }
            Years years;
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT MIN(year) AS min,MAX(year) AS max,COUNT(year) FROM books")) {
                rs.next();
                years = new Years(nullableInt(rs, "min"), nullableInt(rs, "max"), rs.getLong(3));
            }
            List<Author> authors = new ArrayList<>();
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("""
                         SELECT author,COUNT(*) AS books FROM books WHERE author IS NOT NULL
                         GROUP BY author ORDER BY books DESC,author ASC LIMIT 100
                         """)) {
                while (rs.next()) authors.add(new Author(rs.getString(1), rs.getLong(2)));
            }
            return new Filters(List.copyOf(languages), years, List.copyOf(authors),
                    state.booksIndexed(), state.version());
        }

        @Override
        public void close() throws SQLException {
            // Closing rolls back the read transaction and releases its WAL snapshot.
            connection.close();
        }
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static void bind(PreparedStatement statement, List<?> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) statement.setObject(i + 1, values.get(i));
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public record State(long version, long booksIndexed) {}
    public record Result(int book_id, String title, String author, String language, Integer year, double score) {}
    public record Page(long total, List<Result> results) {}
    public record Book(int book_id, String title, String author, String language, Integer year,
                       long word_count, String indexed_at) {}
    public record Language(String code, long books) {}
    public record Years(Integer min, Integer max, long books_with_year) {}
    public record Author(String name, long books) {}
    public record Filters(List<Language> languages, Years years, List<Author> authors,
                          long books_indexed, long index_version) {}
}
