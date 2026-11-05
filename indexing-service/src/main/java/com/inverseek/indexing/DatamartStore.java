package com.inverseek.indexing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class DatamartStore {
    public static final int SCHEMA_VERSION = 1;
    private final String databaseUrl;

    public DatamartStore(Path databasePath) throws Exception {
        Path absolute = databasePath.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        databaseUrl = "jdbc:sqlite:" + absolute;
        initialize();
    }

    private Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection(databaseUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=30000");
            stmt.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    private void initialize() throws SQLException {
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            int version;
            try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                rs.next();
                version = rs.getInt(1);
            }
            if (version > SCHEMA_VERSION) throw new SQLException("Unsupported datamart schema version: " + version);
            try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!rs.next() || !"wal".equalsIgnoreCase(rs.getString(1)))
                    throw new SQLException("Cannot enable SQLite WAL");
            }
            if (version == SCHEMA_VERSION) return;
            conn.setAutoCommit(false);
            try {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS books (
                        book_id TEXT PRIMARY KEY, title TEXT, author TEXT, language TEXT, body_path TEXT,
                        author_norm TEXT, year INTEGER, word_count INTEGER NOT NULL DEFAULT 0,
                        content_sha TEXT, indexed_at TEXT
                    )
                    """);
                Set<String> columns = columns(conn, "books");
                for (String definition : List.of("author_norm TEXT", "year INTEGER",
                        "word_count INTEGER NOT NULL DEFAULT 0", "content_sha TEXT", "indexed_at TEXT")) {
                    if (!columns.contains(definition.split(" ")[0]))
                        stmt.executeUpdate("ALTER TABLE books ADD COLUMN " + definition);
                }
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS postings (
                        term TEXT NOT NULL, book_id TEXT NOT NULL, tf INTEGER NOT NULL CHECK(tf > 0),
                        PRIMARY KEY(term, book_id), FOREIGN KEY(book_id) REFERENCES books(book_id)
                    )
                    """);
                Set<String> postingColumns = columns(conn, "postings");
                if (postingColumns.contains("word"))
                    stmt.executeUpdate("ALTER TABLE postings RENAME COLUMN word TO term");
                if (postingColumns.contains("frequency"))
                    stmt.executeUpdate("ALTER TABLE postings RENAME COLUMN frequency TO tf");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_postings_book ON postings(book_id)");
                // Retained as a compatibility mirror for existing operational tooling.
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS index_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS index_state (
                        id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL,
                        books_indexed INTEGER NOT NULL, last_update TEXT
                    )
                    """);
                migrateMetadata(conn);
                stmt.executeUpdate("""
                    INSERT OR IGNORE INTO index_state(id, version, books_indexed, last_update)
                    SELECT 1, CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END, COUNT(*),
                           (SELECT value FROM index_metadata WHERE key='last_update') FROM books
                    """);
                stmt.executeUpdate("PRAGMA user_version=" + SCHEMA_VERSION);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private static Set<String> columns(Connection conn, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) names.add(rs.getString("name"));
        }
        return names;
    }

    private static void migrateMetadata(Connection conn) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT book_id,title,author,language FROM books")) {
            while (rs.next()) rows.add(new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)});
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
            UPDATE books SET title=?, author=?, author_norm=?, language=?,
                word_count=COALESCE((SELECT SUM(tf) FROM postings WHERE postings.book_id=books.book_id),0),
                indexed_at=COALESCE(indexed_at,(SELECT value FROM index_metadata WHERE key='last_update'))
            WHERE book_id=?
            """)) {
            for (String[] row : rows) {
                stmt.setString(1, HeaderParser.nullable(row[1]));
                stmt.setString(2, HeaderParser.nullable(row[2]));
                stmt.setString(3, HeaderParser.normalizeAuthor(row[2]));
                stmt.setString(4, HeaderParser.languageCode(row[3]));
                stmt.setString(5, row[0]);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
        // No original publication year or body hash is invented during migration.
    }

    public void saveBook(String bookId, HeaderParser.BookMetadata metadata, Path bodyPath,
                         Map<String,Integer> frequencies) throws SQLException {
        saveBook(new BookData(bookId, metadata, bodyPath, frequencies, null));
    }

    public void saveBook(BookData book) throws SQLException {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                writeBook(conn, book, true);
                publish(conn);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private static void writeBook(Connection conn, BookData book, boolean upsert) throws SQLException {
        if (!book.bookId().matches("[1-9][0-9]{0,7}") || Long.parseLong(book.bookId()) > 10_000_000)
            throw new IllegalArgumentException("Invalid book id");
        String sql = """
            INSERT INTO books(book_id,title,author,author_norm,language,year,word_count,content_sha,indexed_at,body_path)
            VALUES(?,?,?,?,?,NULL,?,?,?,?)
            """ + (upsert ? """
            ON CONFLICT(book_id) DO UPDATE SET title=excluded.title,author=excluded.author,
              author_norm=excluded.author_norm,language=excluded.language,year=excluded.year,
              word_count=excluded.word_count,content_sha=excluded.content_sha,
              indexed_at=excluded.indexed_at,body_path=excluded.body_path
            """ : "");
        long wordCount = 0;
        for (int tf : book.frequencies().values()) {
            if (tf <= 0) throw new IllegalArgumentException("Term frequencies must be positive");
            wordCount = Math.addExact(wordCount, tf);
        }
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, book.bookId());
            stmt.setString(2, HeaderParser.nullable(book.metadata().title()));
            stmt.setString(3, HeaderParser.nullable(book.metadata().author()));
            stmt.setString(4, HeaderParser.normalizeAuthor(book.metadata().author()));
            stmt.setString(5, HeaderParser.languageCode(book.metadata().language()));
            stmt.setLong(6, wordCount);
            stmt.setString(7, book.contentSha());
            stmt.setString(8, Instant.now().toString());
            // Relative to the datalake root, as in the marker: never tied to one machine.
            stmt.setString(9, book.bodyPath().toString().replace('\\', '/'));
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM postings WHERE book_id=?")) {
            stmt.setString(1, book.bookId());
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO postings(term,book_id,tf) VALUES(?,?,?)")) {
            for (var term : book.frequencies().entrySet()) {
                stmt.setString(1, term.getKey());
                stmt.setString(2, book.bookId());
                stmt.setInt(3, term.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void rebuildIndex(List<BookData> books) throws SQLException {
        try {
            rebuildFrom(books.stream().map(book -> (BookLoader) () -> book).toList());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Replaces the whole index in one transaction, loading each book only while it is being written. */
    public void rebuildFrom(List<BookLoader> books) throws SQLException, java.io.IOException {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("DELETE FROM postings");
                    stmt.executeUpdate("DELETE FROM books");
                }
                for (BookLoader book : books) writeBook(conn, book.load(), false);
                publish(conn);
                conn.commit();
            } catch (SQLException | java.io.IOException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    @FunctionalInterface
    public interface BookLoader {
        BookData load() throws java.io.IOException;
    }

    public void clearIndex() throws SQLException { rebuildIndex(List.of()); }

    private static void publish(Connection conn) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement stmt = conn.prepareStatement("""
            UPDATE index_state SET version=version+1,
              books_indexed=(SELECT COUNT(*) FROM books),last_update=? WHERE id=1
            """)) {
            stmt.setString(1, now);
            if (stmt.executeUpdate() != 1) throw new SQLException("Missing index_state singleton");
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
            INSERT INTO index_metadata(key,value) VALUES('last_update',?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            """)) {
            stmt.setString(1, now);
            stmt.executeUpdate();
        }
    }

    public Snapshot snapshot() throws SQLException {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                long version; int count; String last;
                try (ResultSet rs = stmt.executeQuery("SELECT version,books_indexed,last_update FROM index_state WHERE id=1")) {
                    if (!rs.next()) throw new SQLException("Missing index_state singleton");
                    version=rs.getLong(1); count=rs.getInt(2); last=rs.getString(3);
                }
                long pages; long size;
                try (ResultSet rs = stmt.executeQuery("PRAGMA page_count")) { rs.next(); pages=rs.getLong(1); }
                try (ResultSet rs = stmt.executeQuery("PRAGMA page_size")) { rs.next(); size=rs.getLong(1); }
                conn.commit();
                return new Snapshot(version,count,last,Math.round(pages*size / 1048576.0 * 100.0)/100.0);
            }
        }
    }

    public int countBooks() throws SQLException { return snapshot().booksIndexed(); }
    public String getLastUpdate() throws SQLException { return snapshot().lastUpdate(); }
    public double getIndexSizeMB() throws SQLException { return snapshot().sizeMB(); }

    public record Snapshot(long version, int booksIndexed, String lastUpdate, double sizeMB) {}
    public record BookData(String bookId, HeaderParser.BookMetadata metadata, Path bodyPath,
                           Map<String,Integer> frequencies, String contentSha) {
        public BookData(String bookId, HeaderParser.BookMetadata metadata, Path bodyPath,
                        Map<String,Integer> frequencies) {
            this(bookId,metadata,bodyPath,frequencies,null);
        }
    }
}
