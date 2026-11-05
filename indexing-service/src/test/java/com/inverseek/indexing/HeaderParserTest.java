
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
        assertEquals("en", metadata.language());
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
        assertEquals("es", metadata.language());
    }

    @Test
    void shouldReturnNullForMissingFields() throws IOException {

        Path header = tempDir.resolve("123.header.txt");

        Files.writeString(header, "Title: Example Book");

        HeaderParser.BookMetadata metadata =
                HeaderParser.parse(header);

        assertEquals("Example Book", metadata.title());
        assertNull(metadata.author());
        assertNull(metadata.language());
    }

    @Test
    void shouldFailWhenHeaderDoesNotExist() {

        Path header = tempDir.resolve("missing.header.txt");

        assertThrows(
                IOException.class,
                () -> HeaderParser.parse(header)
        );
    }

    @Test
    void normalizesAccentsAndDoesNotConsumeFollowingLinesForEmptyFields() {
        var metadata = HeaderParser.parseContent("Title: Test\nAuthor: \nLanguage: French\n");
        assertNull(metadata.author());
        assertEquals("fr", metadata.language());
        assertEquals("jose alvarez", HeaderParser.normalizeAuthor("José Álvarez"));
        assertEquals("en", HeaderParser.languageCode("EN"));
        assertEquals("es", HeaderParser.languageCode("spa"));
        assertNull(HeaderParser.languageCode("unrecognized language"));
    }
}
