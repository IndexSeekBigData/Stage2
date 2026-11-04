
package com.inverseek.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HeaderParserTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExtractBookMetadata() throws IOException {

        Path header = tempDir.resolve("123.header.txt");

        Files.writeString(header, """
                Title: Example Book
                Author: John Smith
                Language: English
                """);

        HeaderParser.BookMetadata metadata =
                HeaderParser.parse(header);

        assertEquals("Example Book", metadata.title());
        assertEquals("John Smith", metadata.author());
        assertEquals("English", metadata.language());
    }

    @Test
    void shouldIgnoreFieldCase() throws IOException {

        Path header = tempDir.resolve("123.header.txt");

        Files.writeString(header, """
                TITLE: Example Book
                author: John Smith
                LANGUAGE: Spanish
                """);

        HeaderParser.BookMetadata metadata =
                HeaderParser.parse(header);

        assertEquals("Example Book", metadata.title());
        assertEquals("John Smith", metadata.author());
        assertEquals("Spanish", metadata.language());
    }

    @Test
    void shouldReturnUnknownForMissingFields() throws IOException {

        Path header = tempDir.resolve("123.header.txt");

        Files.writeString(header, "Title: Example Book");

        HeaderParser.BookMetadata metadata =
                HeaderParser.parse(header);

        assertEquals("Example Book", metadata.title());
        assertEquals("Unknown", metadata.author());
        assertEquals("Unknown", metadata.language());
    }

    @Test
    void shouldFailWhenHeaderDoesNotExist() {

        Path header = tempDir.resolve("missing.header.txt");

        assertThrows(
                IOException.class,
                () -> HeaderParser.parse(header)
        );
    }
}
