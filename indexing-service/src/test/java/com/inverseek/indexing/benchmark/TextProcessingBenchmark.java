package com.inverseek.indexing.benchmark;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CPU work that Indexing does per book before writing to SQLite: metadata extraction from the
 * header, tokenization of the body and the SHA-256 that is checked against the datalake marker.
 * Indexing runs this part in parallel in its request threads, so it is measured with
 * {@code -t 1, 2, 4, 8} (see benchmarks/run-jmh.ps1). File reading is excluded.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms1G", "-Xmx4G"})
@State(Scope.Benchmark)
public class TextProcessingBenchmark {

    private static final int BOOK_ID = 1342;

    @Param({"SMALL", "MEDIUM", "LARGE"})
    public SyntheticBooks.Size size;

    private String header;
    private String body;
    private byte[] bodyBytes;

    @Setup(Level.Trial)
    public void prepare() {
        // Stored files use LF: Ingestion normalizes line ends before publishing.
        header = SyntheticBooks.header(BOOK_ID, "\n");
        body = SyntheticBooks.body(BOOK_ID, size, "\n");
        bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    }

    /** Tokenization alone, the dominant cost. */
    @Benchmark
    public Map<String, Integer> tokenize() {
        return Tokenizer.tokenize(body);
    }

    /** Everything Indexing computes for one book: metadata, term frequencies and body hash. */
    @Benchmark
    public void prepareBook(Blackhole blackhole) throws NoSuchAlgorithmException {
        blackhole.consume(HeaderParser.parseContent(header));
        blackhole.consume(Tokenizer.tokenize(body));
        blackhole.consume(MessageDigest.getInstance("SHA-256").digest(bodyBytes));
    }
}
