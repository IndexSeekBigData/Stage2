package com.inverseek.ingestion.benchmark;

import com.inverseek.ingestion.BookParser;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Cost of splitting a raw Gutenberg file into header and body ({@link BookParser#split}), which
 * Ingestion runs on every download. The input is prepared once per trial, outside the measurement.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms1G", "-Xmx4G"})
@State(Scope.Benchmark)
public class BookParserBenchmark {

    private static final int BOOK_ID = 1342;

    @Param({"SMALL", "MEDIUM", "LARGE"})
    public SyntheticBooks.Size size;

    private String raw;

    @Setup(Level.Trial)
    public void prepare() {
        raw = SyntheticBooks.raw(BOOK_ID, size);
    }

    @Benchmark
    public BookParser.SplitBook split() {
        return BookParser.split(BOOK_ID, raw);
    }
}
