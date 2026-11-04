package com.inverseek.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class TestFixtures {
    private TestFixtures() {
    }

    static String sampleBook() {
        try (InputStream in = TestFixtures.class.getResourceAsStream("/pg-sample.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
