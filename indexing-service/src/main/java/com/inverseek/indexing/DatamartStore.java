
package com.inverseek.indexing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import java.util.List;

public class DatamartStore {

    private final String databaseUrl;

    public DatamartStore(Path databasePath) throws Exception {

        Path parent = databasePath.toAbsolutePath().getParent();
        Files.createDirectories(parent);

        this.databaseUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();

        createTables();
    }

    private void createTables() throws SQLException {

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS books (
                    book_id TEXT PRIMARY KEY,
                    title TEXT,
                    author TEXT,
                    language TEXT,
                    body_path TEXT
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS postings (
                    word TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    frequency INTEGER NOT NULL,
                    PRIMARY KEY (word, book_id),
                    FOREIGN KEY (book_id) REFERENCES books(book_id)
                )
            """);

            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_postings_book
                ON postings(book_id)
            """);

            // Store general index information
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS index_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);
        }
    }

    public void saveBook(
            String bookId,
            HeaderParser.BookMetadata metadata,
            Path bodyPath,
            Map<String, Integer> frequencies
    ) throws SQLException {

        try (Connection conn = DriverManager.getConnection(databaseUrl)) {

            conn.setAutoCommit(false);

            try {
                try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT OR REPLACE INTO books
                    (book_id, title, author, language, body_path)
                    VALUES (?, ?, ?, ?, ?)
                """)) {

                    stmt.setString(1, bookId);
                    stmt.setString(2, metadata.title());
                    stmt.setString(3, metadata.author());
                    stmt.setString(4, metadata.language());
                    stmt.setString(5, bodyPath.toString());

                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM postings WHERE book_id = ?")) {

                    stmt.setString(1, bookId);
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO postings (word, book_id, frequency)
                    VALUES (?, ?, ?)
                """)) {

                    for (Map.Entry<String, Integer> entry :
                            frequencies.entrySet()) {

                        stmt.setString(1, entry.getKey());
                        stmt.setString(2, bookId);
                        stmt.setInt(3, entry.getValue());

                        stmt.addBatch();
                    }

                    stmt.executeBatch();
                }

                saveLastUpdate(conn);
                conn.commit();

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public void clearIndex() throws SQLException {

        try (Connection conn = DriverManager.getConnection(databaseUrl)) {

            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {

                // Delete indexed words first
                stmt.executeUpdate("DELETE FROM postings");

                // Delete book metadata
                stmt.executeUpdate("DELETE FROM books");

                conn.commit();

            } catch (SQLException e) {

                conn.rollback();
                throw e;
            }
        }
    }

    public void rebuildIndex(
            List<BookData> books
    ) throws SQLException {

        try (Connection conn = DriverManager.getConnection(databaseUrl)) {

            conn.setAutoCommit(false);

            try {
                // Clear previous index
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("DELETE FROM postings");
                    stmt.executeUpdate("DELETE FROM books");
                }

                // Prepare metadata insertion
                try (PreparedStatement bookStmt = conn.prepareStatement("""
                    INSERT INTO books
                    (book_id, title, author, language, body_path)
                    VALUES (?, ?, ?, ?, ?)
                    """);
                     PreparedStatement postingStmt = conn.prepareStatement("""
                    INSERT INTO postings
                    (word, book_id, frequency)
                    VALUES (?, ?, ?)
                    """)) {

                    for (BookData book : books) {

                        bookStmt.setString(1, book.bookId());
                        bookStmt.setString(2, book.metadata().title());
                        bookStmt.setString(3, book.metadata().author());
                        bookStmt.setString(4, book.metadata().language());
                        bookStmt.setString(5, book.bodyPath().toString());
                        bookStmt.executeUpdate();

                        for (Map.Entry<String, Integer> entry :
                                book.frequencies().entrySet()) {

                            postingStmt.setString(1, entry.getKey());
                            postingStmt.setString(2, book.bookId());
                            postingStmt.setInt(3, entry.getValue());
                            postingStmt.addBatch();
                        }

                        postingStmt.executeBatch();
                        postingStmt.clearBatch();
                    }
                }

                // Save timestamp and publish the complete index
                saveLastUpdate(conn);
                conn.commit();

            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public record BookData(
            String bookId,
            HeaderParser.BookMetadata metadata,
            Path bodyPath,
            Map<String, Integer> frequencies
    ) {}


    public int countBooks() throws SQLException {

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM books"
             )) {

            return rs.getInt(1);
        }
    }

    // Save the timestamp of the last index update
    public void updateLastUpdate() throws SQLException {

        String timestamp = java.time.Instant.now().toString();

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             PreparedStatement stmt = conn.prepareStatement("""
                 INSERT INTO index_metadata (key, value)
                 VALUES ('last_update', ?)
                 ON CONFLICT(key)
                 DO UPDATE SET value = excluded.value
             """)) {

            stmt.setString(1, timestamp);
            stmt.executeUpdate();
        }
    }

    // Retrieve the timestamp of the last index update
    public String getLastUpdate() throws SQLException {

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             PreparedStatement stmt = conn.prepareStatement("""
                 SELECT value
                 FROM index_metadata
                 WHERE key = 'last_update'
             """);
             ResultSet rs = stmt.executeQuery()) {

            return rs.next() ? rs.getString("value") : null;
        }
    }


    private void saveLastUpdate(Connection conn) throws SQLException {

        String timestamp = java.time.Instant.now().toString();

        try (PreparedStatement stmt = conn.prepareStatement("""
        INSERT INTO index_metadata (key, value)
        VALUES ('last_update', ?)
        ON CONFLICT(key)
        DO UPDATE SET value = excluded.value
    """)) {

            stmt.setString(1, timestamp);
            stmt.executeUpdate();
        }
    }


    public double getIndexSizeMB() throws SQLException {

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             Statement stmt = conn.createStatement()) {

            long pageCount;
            long pageSize;

            try (ResultSet rs = stmt.executeQuery("PRAGMA page_count")) {
                pageCount = rs.getLong(1);
            }

            try (ResultSet rs = stmt.executeQuery("PRAGMA page_size")) {
                pageSize = rs.getLong(1);
            }

            double sizeMB =
                    (pageCount * pageSize) / (1024.0 * 1024.0);

            return Math.round(sizeMB * 100.0) / 100.0;
        }
    }


}
