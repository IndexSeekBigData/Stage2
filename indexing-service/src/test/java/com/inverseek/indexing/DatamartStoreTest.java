package com.inverseek.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
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
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT COUNT(*)
                     FROM sqlite_master
                     WHERE type = 'table'
                     AND name IN ('books', 'postings', 'index_metadata')
                     """)) {

            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
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
             ResultSet rs = stmt.executeQuery("""
                     SELECT title, author, language
                     FROM books
                     WHERE book_id = '123'
                     """)) {

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
                "123",
                metadata,
                Path.of("123.body.txt"),
                Map.of("data", 3, "old", 2)
        );

        store.saveBook(
                "123",
                metadata,
                Path.of("123.body.txt"),
                Map.of("data", 5, "new", 1)
        );

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM books")) {

                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }

            try (ResultSet rs = stmt.executeQuery("""
                    SELECT COUNT(*)
                    FROM postings
                    WHERE book_id = '123'
                    """)) {

                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }

            try (ResultSet rs = stmt.executeQuery("""
                    SELECT frequency
                    FROM postings
                    WHERE word = 'data' AND book_id = '123'
                    """)) {

                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1));
            }

            try (ResultSet rs = stmt.executeQuery("""
                    SELECT COUNT(*)
                    FROM postings
                    WHERE word = 'old' AND book_id = '123'
                    """)) {

                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void shouldStoreLastUpdate() throws Exception {

        Path dbPath = tempDir.resolve("datamarts.db");
        DatamartStore store = new DatamartStore(dbPath);

        assertNull(store.getLastUpdate());

        store.saveBook(
                "123",
                new HeaderParser.BookMetadata(
                        "Example Book", "John Smith", "English"
                ),
                Path.of("123.body.txt"),
                Map.of("example", 2)
        );

        assertNotNull(store.getLastUpdate());
    }

    @Test
    void shouldRebuildIndex() throws Exception {

        Path dbPath = tempDir.resolve("datamarts.db");
        DatamartStore store = new DatamartStore(dbPath);

        HeaderParser.BookMetadata metadata =
                new HeaderParser.BookMetadata(
                        "Example Book", "John Smith", "English"
                );

        store.saveBook(
                "123",
                metadata,
                Path.of("123.body.txt"),
                Map.of("old", 2)
        );

        store.rebuildIndex(
                List.of(
                        new DatamartStore.BookData(
                                "456",
                                metadata,
                                Path.of("456.body.txt"),
                                Map.of("new", 3)
                        )
                )
        );

        assertEquals(1, store.countBooks());

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT book_id FROM books")) {

                assertTrue(rs.next());
                assertEquals("456", rs.getString("book_id"));
                assertFalse(rs.next());
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT word, frequency FROM postings")) {

                assertTrue(rs.next());
                assertEquals("new", rs.getString("word"));
                assertEquals(3, rs.getInt("frequency"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void shouldRollbackFailedRebuild() throws Exception {

        Path dbPath = tempDir.resolve("datamarts.db");
        DatamartStore store = new DatamartStore(dbPath);

        HeaderParser.BookMetadata metadata =
                new HeaderParser.BookMetadata(
                        "Example Book", "John Smith", "English"
                );

        store.saveBook(
                "123",
                metadata,
                Path.of("123.body.txt"),
                Map.of("original", 2)
        );

        String previousUpdate = store.getLastUpdate();

        assertThrows(
                java.sql.SQLException.class,
                () -> store.rebuildIndex(
                        List.of(
                                new DatamartStore.BookData(
                                        "456",
                                        metadata,
                                        Path.of("456.body.txt"),
                                        Map.of("valid", 1)
                                ),
                                new DatamartStore.BookData(
                                        "456",
                                        metadata,
                                        Path.of("456.body.txt"),
                                        Map.of("duplicate", 1)
                                )
                        )
                )
        );

        assertEquals(1, store.countBooks());
        assertEquals(previousUpdate, store.getLastUpdate());

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT book_id FROM books")) {

                assertTrue(rs.next());
                assertEquals("123", rs.getString("book_id"));
                assertFalse(rs.next());
            }

            try (ResultSet rs = stmt.executeQuery("""
                    SELECT word, frequency
                    FROM postings
                    WHERE book_id = '123'
                    """)) {

                assertTrue(rs.next());
                assertEquals("original", rs.getString("word"));
                assertEquals(2, rs.getInt("frequency"));
                assertFalse(rs.next());
            }
        }
    }
}
