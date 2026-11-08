package com.inverseek.ingestion.benchmark;

import com.inverseek.ingestion.BookParser;
import com.inverseek.ingestion.BookRecord;
import com.inverseek.ingestion.Datalake;
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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Cost of publishing one book in the datalake ({@link Datalake#store}): header, body and marker
 * are written with SYNC under {@code .tmp/} and moved into the partition, the marker last.
 * It runs in a temporary folder on the local disk, without network. Each benchmark thread
 * rewrites its own book id, so the datalake does not grow during the run.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms1G", "-Xmx4G"})
@State(Scope.Benchmark)
public class DatalakeWriteBenchmark {

    @Param({"SMALL", "MEDIUM", "LARGE"})
    public SyntheticBooks.Size size;

    private Path root;
    private Datalake datalake;
    private String header;
    private String body;
    private final AtomicInteger nextId = new AtomicInteger(1);

    @State(Scope.Thread)
    public static class ThreadBook {
        int bookId;

        @Setup(Level.Trial)
        public void assign(DatalakeWriteBenchmark benchmark) {
            bookId = benchmark.nextId.getAndIncrement();
        }
    }

    @Setup(Level.Trial)
    public void prepare() throws IOException {
        root = Files.createTempDirectory("inverseek-bench-datalake");
        datalake = new Datalake(root, ZoneOffset.UTC, Clock.systemUTC());
        BookParser.SplitBook split = BookParser.split(1342, SyntheticBooks.raw(1342, size));
        header = split.header();
        body = split.body();
    }

    @Benchmark
    public BookRecord store(ThreadBook book) {
        return datalake.store(book.bookId, header, body);
    }

    @TearDown(Level.Trial)
    public void cleanUp() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
