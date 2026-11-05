package com.inverseek.search;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TestIndex {
    private TestIndex() {}

    static Config config(Path database) {
        return new Config(0, database, 20, 100, List.of("*"), 5000, 32);
    }

    static Path create(Path directory) throws Exception {
        Path database = directory.resolve("datamarts.db");
        try (Connection connection = writer(database); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA user_version=1");
            statement.execute("""
                    CREATE TABLE books(
                      book_id TEXT PRIMARY KEY,title TEXT,author TEXT,author_norm TEXT,language TEXT,
                      year INTEGER,word_count INTEGER NOT NULL,content_sha TEXT,indexed_at TEXT,body_path TEXT)
                    """);
            statement.execute("""
                    CREATE TABLE postings(term TEXT NOT NULL,book_id TEXT NOT NULL,tf INTEGER NOT NULL,
                      PRIMARY KEY(term,book_id),FOREIGN KEY(book_id) REFERENCES books(book_id))
                    """);
            statement.execute("CREATE INDEX idx_postings_book ON postings(book_id)");
            statement.execute("""
                    CREATE TABLE index_state(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL,
                      books_indexed INTEGER NOT NULL,last_update TEXT)
                    """);
            connection.setAutoCommit(false);
            addBook(connection, 2, "First", "Jane Austen", "en", 1813, Map.of("love", 3, "book", 1, "adventure", 1));
            addBook(connection, 10, "Second", "Jane Austen", "en", null, Map.of("love", 3, "book", 2));
            addBook(connection, 100, "Third", "José Álvarez", "es", 1900, Map.of("love", 1, "book", 3, "adventure", 2));
            addBook(connection, 200, "Fourth", "100% Real_Writer!", "en", 2000, Map.of("love", 2, "book", 1));
            addBook(connection, 300, "Fifth", null, null, null, Map.of("lonely", 1));
            addBook(connection, 400, "Sixth", "Other", "fr", 1950, Map.of("love", 1, "book", 1, "adventure", 2));
            statement.execute("INSERT INTO index_state VALUES(1,16,6,'2026-11-05T00:00:00Z')");
            connection.commit();
        }
        return database;
    }

    static Connection writer(Path database) throws SQLException {
        var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    static void addBook(Connection connection, int id, String title, String author, String language,
                        Integer year, Map<String, Integer> frequencies) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO books VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, Integer.toString(id));
            statement.setString(2, title);
            statement.setString(3, author);
            statement.setString(4, author == null ? null : Normalizer.normalize(author, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT));
            statement.setString(5, language);
            statement.setObject(6, year);
            statement.setLong(7, frequencies.values().stream().mapToLong(Integer::longValue).sum());
            statement.setString(8, "a".repeat(64));
            statement.setString(9, "2026-11-05T00:00:00Z");
            statement.setString(10, "private/body.txt");
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("INSERT INTO postings VALUES(?,?,?)")) {
            for (var term : frequencies.entrySet()) {
                statement.setString(1, term.getKey());
                statement.setString(2, Integer.toString(id));
                statement.setInt(3, term.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    static void publishExtraBook(Path database) throws SQLException {
        try (var connection = writer(database); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            addBook(connection, 500, "New", "New Author", "en", 2001, Map.of("love", 1));
            statement.execute("UPDATE index_state SET version=17,books_indexed=7,last_update='2026-11-05T01:00:00Z'");
            connection.commit();
        }
    }
}
