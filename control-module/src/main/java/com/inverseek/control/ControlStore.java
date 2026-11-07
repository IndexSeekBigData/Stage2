package com.inverseek.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistent workflow state in control.db. Only the Control Module opens this database.
 * Every state change is one atomic SQL statement, so threads and replicas cannot claim the same book,
 * and every completion requires the current lease owner, so a worker that lost its lease changes nothing.
 */
public final class ControlStore {
    private static final String COLUMNS = """
            book_id,state,attempts,lease_owner,lease_until,next_attempt_at,last_error,datalake_path,
            created_at,updated_at,queued_at,download_started_at,downloaded_at,index_started_at,indexed_at
            """;

    private final String url;
    private final Clock clock;

    public ControlStore(Path databasePath, Clock clock) throws SQLException {
        this.clock = clock;
        Path absolute = databasePath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the control database folder", e);
        }
        url = "jdbc:sqlite:" + absolute;
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!rs.next() || !"wal".equalsIgnoreCase(rs.getString(1)))
                    throw new SQLException("Cannot enable SQLite WAL");
            }
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS books (
                        book_id INTEGER PRIMARY KEY,
                        state TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        lease_owner TEXT,
                        lease_until INTEGER,
                        next_attempt_at INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        datalake_path TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        queued_at INTEGER NOT NULL,
                        download_started_at INTEGER,
                        downloaded_at INTEGER,
                        index_started_at INTEGER,
                        indexed_at INTEGER
                    )
                    """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_books_claim ON books(state,next_attempt_at,queued_at,book_id)");
        }
    }

    private Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection(url);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=30000");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    private long now() {
        return clock.millis();
    }

    public record Registration(int inserted, int ignored) {}

    /** Idempotent: a book that is already registered keeps its state. */
    public Registration register(Collection<Integer> ids) throws SQLException {
        long now = now();
        int inserted = 0;
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT OR IGNORE INTO books(book_id,state,created_at,updated_at,queued_at)
                    VALUES(?,'PENDING',?,?,?)
                    """)) {
                for (int id : ids) {
                    stmt.setInt(1, id);
                    stmt.setLong(2, now);
                    stmt.setLong(3, now);
                    stmt.setLong(4, now);
                    inserted += stmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
        return new Registration(inserted, ids.size() - inserted);
    }

    public record Claim(int bookId, BookState state, int attempts, String owner) {}

    /** Atomically claims the oldest book waiting in {@code from}, moving it to {@code to}. */
    private Optional<Claim> claim(BookState from, BookState to, String startedColumn, String owner, long leaseMillis)
            throws SQLException {
        long now = now();
        String sql = "UPDATE books SET state=?,lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=?,"
                + startedColumn + "=? WHERE book_id=(SELECT book_id FROM books WHERE state=? AND next_attempt_at<=?"
                + " ORDER BY queued_at,book_id LIMIT 1) AND state=? RETURNING book_id,attempts";
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, to.name());
            stmt.setString(2, owner);
            stmt.setLong(3, now + leaseMillis);
            stmt.setLong(4, now);
            stmt.setLong(5, now);
            stmt.setString(6, from.name());
            stmt.setLong(7, now);
            stmt.setString(8, from.name());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Claim(rs.getInt(1), to, rs.getInt(2), owner))
                        : Optional.empty();
            }
        }
    }

    public Optional<Claim> claimDownload(String owner, long leaseMillis) throws SQLException {
        return claim(BookState.PENDING, BookState.DOWNLOADING, "download_started_at", owner, leaseMillis);
    }

    public Optional<Claim> claimIndex(String owner, long leaseMillis) throws SQLException {
        return claim(BookState.DOWNLOADED, BookState.INDEXING, "index_started_at", owner, leaseMillis);
    }

    private boolean transition(Claim claim, String setClause, Object... values) throws SQLException {
        String sql = "UPDATE books SET " + setClause + ",lease_owner=NULL,lease_until=NULL,updated_at=?"
                + " WHERE book_id=? AND state=? AND lease_owner=?";
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            for (Object value : values) stmt.setObject(i++, value);
            stmt.setLong(i++, now());
            stmt.setInt(i++, claim.bookId());
            stmt.setString(i++, claim.state().name());
            stmt.setString(i, claim.owner());
            return stmt.executeUpdate() == 1;
        }
    }

    /** Returns false when the worker lost its lease; attempts restart for the next phase. */
    public boolean completeDownload(Claim claim, String path) throws SQLException {
        return transition(claim, "state='DOWNLOADED',attempts=0,last_error=NULL,next_attempt_at=0,datalake_path=?,downloaded_at=?",
                path, now());
    }

    public boolean completeIndex(Claim claim) throws SQLException {
        return transition(claim, "state='INDEXED',last_error=NULL,next_attempt_at=0,indexed_at=?", now());
    }

    public boolean markFinal(Claim claim, BookState state, String error) throws SQLException {
        return transition(claim, "state=?,last_error=?", state.name(), error);
    }

    private static BookState waitingState(BookState state) {
        return state == BookState.INDEXING ? BookState.DOWNLOADED : BookState.PENDING;
    }

    /** A failed attempt: the book waits and retries, or becomes FAILED when its attempts are exhausted. */
    public boolean failAttempt(Claim claim, String error, int maxAttempts, long retryDelayMillis) throws SQLException {
        if (claim.attempts() >= maxAttempts) return markFinal(claim, BookState.FAILED, error);
        long delay = retryDelayMillis << Math.max(0, claim.attempts() - 1);
        return transition(claim, "state=?,last_error=?,next_attempt_at=?",
                waitingState(claim.state()).name(), error, now() + delay);
    }

    /** The service could not be reached: nothing was attempted, so the attempt is given back. */
    public boolean releaseUnreachable(Claim claim, String error) throws SQLException {
        return transition(claim, "state=?,last_error=?,attempts=attempts-1",
                waitingState(claim.state()).name(), error);
    }

    /** Releases expired leases: books go back to waiting, or FAILED if their attempts are exhausted. */
    public int releaseExpiredLeases(int maxAttempts) throws SQLException {
        long now = now();
        String expired = "state=? AND lease_until IS NOT NULL AND lease_until<?";
        int released = 0;
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                for (BookState state : List.of(BookState.DOWNLOADING, BookState.INDEXING)) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE books SET state='FAILED',last_error='Lease expired: attempts exhausted',"
                                    + "lease_owner=NULL,lease_until=NULL,updated_at=? WHERE " + expired + " AND attempts>=?")) {
                        stmt.setLong(1, now);
                        stmt.setString(2, state.name());
                        stmt.setLong(3, now);
                        stmt.setInt(4, maxAttempts);
                        released += stmt.executeUpdate();
                    }
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE books SET state=?,last_error='Lease expired',lease_owner=NULL,lease_until=NULL,"
                                    + "next_attempt_at=0,updated_at=? WHERE " + expired)) {
                        stmt.setString(1, waitingState(state).name());
                        stmt.setLong(2, now);
                        stmt.setString(3, state.name());
                        stmt.setLong(4, now);
                        released += stmt.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
        return released;
    }

    public enum RetryResult { RETRIED, UNKNOWN, NOT_RETRYABLE }

    /** FAILED or NOT_FOUND restart from the download; INDEXED only repeats indexing. */
    public RetryResult retry(int bookId) throws SQLException {
        long now = now();
        try (Connection conn = open()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                    UPDATE books SET state='PENDING',attempts=0,last_error=NULL,next_attempt_at=0,updated_at=?,queued_at=?,
                        download_started_at=NULL,downloaded_at=NULL,index_started_at=NULL,indexed_at=NULL
                    WHERE book_id=? AND state IN ('FAILED','NOT_FOUND')
                    """)) {
                stmt.setLong(1, now);
                stmt.setLong(2, now);
                stmt.setInt(3, bookId);
                if (stmt.executeUpdate() == 1) return RetryResult.RETRIED;
            }
            try (PreparedStatement stmt = conn.prepareStatement("""
                    UPDATE books SET state='DOWNLOADED',attempts=0,last_error=NULL,next_attempt_at=0,updated_at=?,
                        downloaded_at=?,index_started_at=NULL,indexed_at=NULL
                    WHERE book_id=? AND state='INDEXED'
                    """)) {
                stmt.setLong(1, now);
                stmt.setLong(2, now);
                stmt.setInt(3, bookId);
                if (stmt.executeUpdate() == 1) return RetryResult.RETRIED;
            }
        }
        return find(bookId).isPresent() ? RetryResult.NOT_RETRYABLE : RetryResult.UNKNOWN;
    }

    public record Book(int bookId, BookState state, int attempts, String leaseOwner, Long leaseUntil, long nextAttemptAt,
                       String lastError, String datalakePath, long createdAt, long updatedAt, long queuedAt,
                       Long downloadStartedAt, Long downloadedAt, Long indexStartedAt, Long indexedAt) {}

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Book book(ResultSet rs) throws SQLException {
        return new Book(rs.getInt("book_id"), BookState.valueOf(rs.getString("state")), rs.getInt("attempts"),
                rs.getString("lease_owner"), nullableLong(rs, "lease_until"), rs.getLong("next_attempt_at"),
                rs.getString("last_error"), rs.getString("datalake_path"), rs.getLong("created_at"),
                rs.getLong("updated_at"), rs.getLong("queued_at"), nullableLong(rs, "download_started_at"),
                nullableLong(rs, "downloaded_at"), nullableLong(rs, "index_started_at"), nullableLong(rs, "indexed_at"));
    }

    public Optional<Book> find(int bookId) throws SQLException {
        try (Connection conn = open();
             PreparedStatement stmt = conn.prepareStatement("SELECT " + COLUMNS + " FROM books WHERE book_id=?")) {
            stmt.setInt(1, bookId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(book(rs)) : Optional.empty();
            }
        }
    }

    public record Page(long total, List<Book> books) {}

    public Page list(BookState state, int offset, int limit) throws SQLException {
        String where = state == null ? "" : " WHERE state=?";
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            long total;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM books" + where)) {
                if (state != null) stmt.setString(1, state.name());
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<Book> books = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT " + COLUMNS + " FROM books" + where + " ORDER BY book_id LIMIT ? OFFSET ?")) {
                int i = 1;
                if (state != null) stmt.setString(i++, state.name());
                stmt.setInt(i++, limit);
                stmt.setInt(i, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) books.add(book(rs));
                }
            }
            conn.rollback();
            return new Page(total, books);
        }
    }

    public Map<BookState, Long> counts() throws SQLException {
        Map<BookState, Long> counts = new EnumMap<>(BookState.class);
        for (BookState state : BookState.values()) counts.put(state, 0L);
        try (Connection conn = open(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT state,COUNT(*) FROM books GROUP BY state")) {
            while (rs.next()) counts.put(BookState.valueOf(rs.getString(1)), rs.getLong(2));
        }
        return counts;
    }

    public long activeLeases() throws SQLException {
        try (Connection conn = open(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM books WHERE lease_owner IS NOT NULL")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public List<Book> indexedBooks() throws SQLException {
        List<Book> books = new ArrayList<>();
        try (Connection conn = open(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT " + COLUMNS + " FROM books WHERE state='INDEXED'")) {
            while (rs.next()) books.add(book(rs));
        }
        return books;
    }
}
