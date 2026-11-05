package com.inverseek.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DatalakeReaderTest {
    @TempDir Path root;
    private Path book(String partition,int id,String time) throws IOException {
        return PublishedBookFixture.create(root,partition,id,"Title: Test\n","Example content",time);
    }

    @Test void findsPublishedBooksInNumericOrder() throws Exception {
        book("20261104/17",100,"2026-11-04T17:00:00Z");
        book("20261105/10",20,"2026-11-05T10:00:00Z");
        var books=DatalakeReader.findBooks(root);
        assertEquals(java.util.List.of("20","100"),books.stream().map(DatalakeReader.BookFiles::bookId).toList());
    }

    @Test void ignoresFilesWithoutMarkerAndTemporaryFilesWithMarker() throws Exception {
        Path dir=Files.createDirectories(root.resolve("20261104/17"));
        Files.writeString(dir.resolve("123.header.txt"),"Title: Test");
        Files.writeString(dir.resolve("123.body.txt"),"Partial");
        book(".tmp/123-in-progress",123,"2026-11-04T17:00:00Z");
        assertTrue(DatalakeReader.findBooks(root).isEmpty());
    }

    @Test void selectsOldestDuplicateRegardlessOfPartitionName() throws Exception {
        book("20261104/17",123,"2026-11-04T19:00:00Z");
        book("20261105/10",123,"2026-11-04T17:00:00Z");
        var books=DatalakeReader.findBooks(root);
        assertEquals(1,books.size());
        assertTrue(books.get(0).bodyPath().startsWith(root.resolve("20261105/10")));
    }

    @Test void rejectsMalformedCommittedMarker() throws Exception {
        Path marker=book("20261104/17",123,"2026-11-04T17:00:00Z");
        Files.writeString(marker,"{}");
        assertThrows(IOException.class,()->DatalakeReader.findBooks(root));
    }

    @Test void rejectsMissingOrResizedBody() throws Exception {
        Path marker=book("20261104/17",123,"2026-11-04T17:00:00Z");
        Files.writeString(marker.resolveSibling("123.body.txt"),"partial");
        assertThrows(IOException.class,()->DatalakeReader.findBooks(root));
        Files.delete(marker.resolveSibling("123.body.txt"));
        assertThrows(IOException.class,()->DatalakeReader.findBooks(root));
    }

    @Test void rejectsInvalidTimestampAndEscapingPaths() throws Exception {
        Path marker=book("20261104/17",123,"bad-time");
        assertThrows(IOException.class,()->DatalakeReader.findBooks(root));
        marker=book("20261104/17",123,"2026-11-04T17:00:00Z");
        Files.writeString(marker,Files.readString(marker).replace("20261104/17/123.body.txt","../123.body.txt"));
        assertThrows(IOException.class,()->DatalakeReader.findBooks(root));
    }

    @Test void failsIfDatalakeDoesNotExist() {
        assertThrows(IOException.class,()->DatalakeReader.findBooks(root.resolve("missing")));
    }
}
