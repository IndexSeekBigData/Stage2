package com.inverseek.systemtests.support;

import org.sqlite.SQLiteConfig;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;

public final class IndexOracle {
    private final Path database;
    public IndexOracle(Path database) { this.database = database; }
    public Connection open() throws Exception {
        SQLiteConfig config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(5000);
        Connection connection = config.createConnection("jdbc:sqlite:" + database);
        try (var stmt = connection.createStatement()) { stmt.execute("PRAGMA query_only=ON"); }
        return connection;
    }
    public Map<String, Integer> postings(int id) throws Exception {
        Map<String,Integer> rows = new TreeMap<>();
        try (var c = open(); var p = c.prepareStatement("SELECT term,tf FROM postings WHERE book_id=?")) {
            p.setString(1, Integer.toString(id));
            try (var rs = p.executeQuery()) { while (rs.next()) rows.put(rs.getString(1), rs.getInt(2)); }
        }
        return rows;
    }
    public Set<Integer> containing(String... terms) throws Exception {
        String placeholders = String.join(",", Collections.nCopies(terms.length, "?"));
        Set<Integer> ids = new TreeSet<>();
        try (var c = open(); var p = c.prepareStatement("SELECT book_id FROM postings WHERE term IN (" + placeholders + ") GROUP BY book_id HAVING COUNT(*)=?")) {
            for (int i=0; i<terms.length; i++) p.setString(i+1,terms[i]);
            p.setInt(terms.length+1,terms.length);
            try (var rs=p.executeQuery()) { while(rs.next()) ids.add(rs.getInt(1)); }
        }
        return ids;
    }
    public String snapshot() throws Exception {
        StringBuilder result = new StringBuilder();
        try (var c = open(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            for (String query : List.of("SELECT * FROM index_state ORDER BY id",
                    "SELECT * FROM books ORDER BY CAST(book_id AS INTEGER)", "SELECT * FROM postings ORDER BY term,book_id")) {
                try (var rs=s.executeQuery(query)) {
                    int columns=rs.getMetaData().getColumnCount();
                    while(rs.next()) { for(int i=1;i<=columns;i++) result.append(rs.getString(i)).append('|'); result.append('\n'); }
                }
            }
        }
        return result.toString();
    }
}
