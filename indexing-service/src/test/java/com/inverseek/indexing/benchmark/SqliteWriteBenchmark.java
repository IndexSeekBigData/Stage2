package com.inverseek.indexing.benchmark;

import com.inverseek.indexing.DatamartStore;
import com.inverseek.indexing.HeaderParser;
import com.inverseek.indexing.Tokenizer;
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
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Cost of publishing one book in the datamart: one SQLite transaction that upserts the book row,
 * replaces its postings and increases the index version ({@link DatamartStore#saveBook}).
 *
 * <p>Each benchmark thread writes its own book id through its own connection, without the
 * in-process lock of IndexingService. SQLite allows a single writer per database, so with
 * {@code -t 2, 4, 8} the threads wait on the database lock (busy_timeout) and the total
 * throughput is expected to stay flat: this is the limit the benchmark is meant to show.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms1G", "-Xmx4G"})
@State(Scope.Benchmark)
public class SqliteWriteBenchmark {

    @Param({"SMALL", "MEDIUM", "LARGE"})
    public SyntheticBooks.Size size;

    private Path directory;
    private DatamartStore store;
    private HeaderParser.BookMetadata metadata;
    private Map<String, Integer> frequencies;
    private final AtomicInteger nextId = new AtomicInteger(1);

    @State(Scope.Thread)
    public static class ThreadBook {
        String bookId;

        @Setup(Level.Trial)
        public void assign(SqliteWriteBenchmark benchmark) {
            bookId = Integer.toString(benchmark.nextId.getAndIncrement());
        }
    }

    @Setup(Level.Trial)
    public void prepare() throws Exception {
        directory = Files.createTempDirectory("inverseek-bench-datamart");
        store = new DatamartStore(directory.resolve("datamarts.db"));
        metadata = HeaderParser.parseContent(SyntheticBooks.header(1342, "\n"));
        frequencies = Tokenizer.tokenize(SyntheticBooks.body(1342, size, "\n"));
    }

    @Benchmark
    public void saveBook(ThreadBook book) throws Exception {
        store.saveBook(new DatamartStore.BookData(book.bookId, metadata,
                Path.of("bench/" + book.bookId + ".body.txt"), frequencies, "0".repeat(64)));
    }

    @TearDown(Level.Trial)
    public void cleanUp() throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
