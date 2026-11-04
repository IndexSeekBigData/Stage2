
package com.inverseek.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatamartStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateDatabaseAndTables() throws Exception {
        Path dbPath = tempDir.resolve("datamarts.db");

        new DatamartStore(dbPath);

        assertTrue(java.nio.file.Files.exists(dbPath));

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery("""
                    SELECT COUNT(*)
                    FROM sqlite_master
                    WHERE type = 'table'
                    AND name IN ('books', 'postings')
                    """);

            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void shouldSaveBookMetadata() throws Exception {
        Path dbPath = tempDir.resolve("datamarts.db");
        DatamartStore store = new DatamartStore(dbPath);

        store.saveBook(
                "123",
                new HeaderParser.BookMetadata(
                        "Example Book", "John Smith", "English"
                ),
                Path.of("123.body.txt"),
                Map.of("example", 2)
        );

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT title, author, language FROM books WHERE book_id = '123'")) {

            assertTrue(rs.next());
            assertEquals("Example Book", rs.getString("title"));
            assertEquals("John Smith", rs.getString("author"));
            assertEquals("English", rs.getString("language"));
        }
    }

    @Test
    void shouldSaveWordFrequencies() throws Exception {
        Path dbPath = tempDir.resolve("datamarts.db");
        DatamartStore store = new DatamartStore(dbPath);

        store.saveBook(
                "123",
                new HeaderParser.BookMetadata(
                        "Example Book", "John Smith", "English"
                ),
                Path.of("123.body.txt"),
                Map.of("data", 3, "science", 2)
        );

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT frequency
                     FROM postings
                     WHERE word = 'data' AND book_id = '123'
                     """)) {

            assertTrue(rs.next());
            assertEquals(3, rs.getInt("frequency"));
        }
    }

    @Test
    void shouldUpdateBookWithoutDuplicates() throws Exception {
        Path dbPath = tempDir.resolve("datamarts.db");
        DatamartStore store = new DatamartStore(dbPath);

        HeaderParser.BookMetadata metadata =
                new HeaderParser.BookMetadata(
                        "Example Book", "John Smith", "English"
                );

        store.saveBook(
                "123", metadata,
                Path.of("123.body.txt"),
                Map.of("data", 3, "old", 2)
        );

        store.saveBook(
                "123", metadata,
                Path.of("123.body.txt"),
                Map.of("data", 5, "new", 1)
        );

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM books")) {
                assertEquals(1, rs.getInt(1));
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM postings WHERE book_id = '123'")) {
                assertEquals(2, rs.getInt(1));
            }

            try (ResultSet rs = stmt.executeQuery("""
                    SELECT frequency FROM postings
                    WHERE word = 'data' AND book_id = '123'
                    """)) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1));
            }

            try (ResultSet rs = stmt.executeQuery("""
                    SELECT COUNT(*) FROM postings
                    WHERE word = 'old' AND book_id = '123'
                    """)) {
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}
