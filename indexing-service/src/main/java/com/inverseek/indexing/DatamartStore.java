
package com.inverseek.indexing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Map;

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

                    for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {

                        stmt.setString(1, entry.getKey());
                        stmt.setString(2, bookId);
                        stmt.setInt(3, entry.getValue());

                        stmt.addBatch();
                    }

                    stmt.executeBatch();
                }

                conn.commit();

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }
}