
package com.inverseek.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatalakeReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldFindCompleteBooks() throws IOException {

        Path folder = tempDir.resolve("20261104/17");
        Files.createDirectories(folder);

        Files.writeString(folder.resolve("123.header.txt"), "Title: Test");
        Files.writeString(folder.resolve("123.body.txt"), "Example content");
        Files.writeString(folder.resolve("123.success.json"), "{}");

        List<DatalakeReader.BookFiles> books =
                DatalakeReader.findBooks(tempDir);

        assertEquals(1, books.size());
        assertEquals("123", books.get(0).bookId());
    }

    @Test
    void shouldIgnoreIncompleteBooks() throws IOException {

        Path folder = tempDir.resolve("20261104/17");
        Files.createDirectories(folder);

        Files.writeString(folder.resolve("123.header.txt"), "Title: Test");
        Files.writeString(folder.resolve("123.body.txt"), "Example content");

        List<DatalakeReader.BookFiles> books =
                DatalakeReader.findBooks(tempDir);

        assertTrue(books.isEmpty());
    }

    @Test
    void shouldFindBooksInDifferentPartitions() throws IOException {

        Path folder1 = tempDir.resolve("20261104/17");
        Path folder2 = tempDir.resolve("20261105/10");

        Files.createDirectories(folder1);
        Files.createDirectories(folder2);

        createBook(folder1, "123");
        createBook(folder2, "456");

        List<DatalakeReader.BookFiles> books =
                DatalakeReader.findBooks(tempDir);

        assertEquals(2, books.size());
    }

    @Test
    void shouldFailWhenDatalakeDoesNotExist() {

        Path missing = tempDir.resolve("missing");

        assertThrows(
                IOException.class,
                () -> DatalakeReader.findBooks(missing)
        );
    }

    private void createBook(Path folder, String bookId)
            throws IOException {

        Files.writeString(
                folder.resolve(bookId + ".header.txt"),
                "Title: Test"
        );

        Files.writeString(
                folder.resolve(bookId + ".body.txt"),
                "Example content"
        );

        Files.writeString(
                folder.resolve(bookId + ".success.json"),
                "{}"
        );
    }
}
