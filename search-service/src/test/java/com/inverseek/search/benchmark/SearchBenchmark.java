package com.inverseek.search.benchmark;

import com.inverseek.search.Config;
import com.inverseek.search.IndexReader;
import com.inverseek.search.QueryParser;
import com.inverseek.search.SearchService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Cost of one search request inside the Search service, without HTTP: parameter parsing, opening
 * the read-only snapshot, document frequencies, ranking and the requested page
 * ({@link QueryParser} and {@link SearchService#search}).
 *
 * <p>The index is the corpus datamart when the environment variable {@code INVERSEEK_BENCH_DATAMART}
 * (or {@code -Dbench.datamart}) points to a {@code datamarts.db}; benchmarks/run-jmh.ps1 sets it from
 * its -Datamart option. Otherwise a synthetic index of 100 books is built in the setup. The database is copied to a temporary folder first, so the original is never touched.
 * Queries use two frequent terms of that index, with and without language and author filters.
 * Search answers requests in parallel threads, so this is measured with {@code -t 1, 2, 4, 8}.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms1G", "-Xmx4G"})
@State(Scope.Benchmark)
public class SearchBenchmark {

    public enum Kind { ONE_WORD, TWO_WORDS, ONE_WORD_FILTERED, TWO_WORDS_FILTERED }

    @Param({"ONE_WORD", "TWO_WORDS", "ONE_WORD_FILTERED", "TWO_WORDS_FILTERED"})
    public Kind kind;

    private Path directory;
    private QueryParser parser;
    private SearchService service;
    private Map<String, List<String>> parameters;

    @Setup(Level.Trial)
    public void prepare() throws Exception {
        directory = Files.createTempDirectory("inverseek-bench-search");
        Path database = directory.resolve("datamarts.db");
        String source = System.getProperty("bench.datamart", System.getenv().getOrDefault("INVERSEEK_BENCH_DATAMART", ""));
        if (source.isBlank()) {
            SyntheticIndex.create(database, 100);
        } else {
            copyDatabase(Path.of(source), database);
        }
        Config config = new Config(0, database, 20, 100, List.of("*"), 5000, 0);
        parser = new QueryParser(config);
        service = new SearchService(new IndexReader(config));
        parameters = parameters(database, kind);
        if (service.search(parser.parse(parameters)).total() == 0)
            throw new IllegalStateException("The benchmark query has no results: " + parameters);
    }

    @Benchmark
    public SearchService.Response search() throws SQLException {
        return service.search(parser.parse(parameters));
    }

    @TearDown(Level.Trial)
    public void cleanUp() throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static void copyDatabase(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("bench.datamart does not exist: " + source);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        for (String suffix : List.of("-wal", "-shm")) {
            Path extra = source.resolveSibling(source.getFileName() + suffix);
            if (Files.isRegularFile(extra))
                Files.copy(extra, target.resolveSibling(target.getFileName() + suffix), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Picks the query terms and filters from the index itself, so the same code works for any corpus. */
    private static Map<String, List<String>> parameters(Path database, Kind kind) throws SQLException {
        List<String> terms = new ArrayList<>();
        String author;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            // Frequent but not universal words (skips the 10 most common ones, such as "the").
            try (ResultSet rs = statement.executeQuery("""
                    SELECT term FROM postings WHERE length(term) >= 4 GROUP BY term
                    ORDER BY COUNT(*) DESC, term ASC LIMIT 2 OFFSET 10
                    """)) {
                while (rs.next()) terms.add(rs.getString(1));
            }
            try (ResultSet rs = statement.executeQuery("""
                    SELECT author FROM books WHERE author IS NOT NULL AND language='en'
                    GROUP BY author ORDER BY COUNT(*) DESC, author ASC LIMIT 1
                    """)) {
                author = rs.next() ? rs.getString(1) : null;
            }
        }
        if (terms.size() < 2) throw new IllegalStateException("The index needs at least two terms");
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        boolean two = kind == Kind.TWO_WORDS || kind == Kind.TWO_WORDS_FILTERED;
        parameters.put("q", List.of(two ? terms.get(0) + " " + terms.get(1) : terms.get(0)));
        if (kind == Kind.ONE_WORD_FILTERED || kind == Kind.TWO_WORDS_FILTERED) {
            parameters.put("language", List.of("en"));
            if (kind == Kind.TWO_WORDS_FILTERED && author != null) parameters.put("author", List.of(author));
        }
        return parameters;
    }

    /** Synthetic datamart with the schema written by the Indexing service. */
    static final class SyntheticIndex {
        private SyntheticIndex() {
        }

        static void create(Path database, int books) throws SQLException {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("""
                        CREATE TABLE books (
                            book_id TEXT PRIMARY KEY, title TEXT, author TEXT, language TEXT, body_path TEXT,
                            author_norm TEXT, year INTEGER, word_count INTEGER NOT NULL DEFAULT 0,
                            content_sha TEXT, indexed_at TEXT)
                        """);
                statement.execute("""
                        CREATE TABLE postings (
                            term TEXT NOT NULL, book_id TEXT NOT NULL, tf INTEGER NOT NULL CHECK(tf > 0),
                            PRIMARY KEY(term, book_id), FOREIGN KEY(book_id) REFERENCES books(book_id))
                        """);
                statement.execute("CREATE INDEX idx_postings_book ON postings(book_id)");
                statement.execute("""
                        CREATE TABLE index_state (
                            id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL,
                            books_indexed INTEGER NOT NULL, last_update TEXT)
                        """);
                connection.setAutoCommit(false);
                Random random = new Random(7);
                for (int id = 1; id <= books; id++) {
                    // Between 20 000 and 600 000 words, like short stories up to long novels.
                    int words = (int) Math.round(20_000 * Math.pow(30, random.nextDouble()));
                    Map<String, Integer> frequencies = new HashMap<>();
                    for (int i = 0; i < words; i++) {
                        String word = SyntheticBooks.word(SyntheticBooks.sampleRank(random));
                        if (word.length() >= 3) frequencies.merge(word, 1, Integer::sum);
                    }
                    String language = id % 10 == 0 ? "es" : id % 10 == 5 ? "fr" : "en";
                    String author = "Author " + (id % 20);
                    addBook(connection, id, author, language, frequencies);
                }
                statement.execute("INSERT INTO index_state VALUES(1, 1, " + books + ", '2026-01-01T00:00:00Z')");
                statement.execute("PRAGMA user_version=1");
                connection.commit();
            }
        }

        private static void addBook(Connection connection, int id, String author, String language,
                                    Map<String, Integer> frequencies) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO books VALUES(?,?,?,?,?,?,NULL,?,?,?)")) {
                statement.setString(1, Integer.toString(id));
                statement.setString(2, "Synthetic Book " + id);
                statement.setString(3, author);
                statement.setString(4, language);
                statement.setString(5, "bench/" + id + ".body.txt");
                statement.setString(6, author.toLowerCase(java.util.Locale.ROOT));
                statement.setLong(7, frequencies.values().stream().mapToLong(Integer::longValue).sum());
                statement.setString(8, "0".repeat(64));
                statement.setString(9, "2026-01-01T00:00:00Z");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO postings VALUES(?,?,?)")) {
                for (Map.Entry<String, Integer> term : frequencies.entrySet()) {
                    statement.setString(1, term.getKey());
                    statement.setString(2, Integer.toString(id));
                    statement.setInt(3, term.getValue());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
    }
}
