package com.inverseek.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PublicationTest {
    @TempDir Path root;
    private DatamartStore.BookData data(String id,String term) {
        return new DatamartStore.BookData(id,new HeaderParser.BookMetadata("Title","José Álvarez","Spanish"),
                root.resolve(id+".body.txt"),Map.of(term,3),"a".repeat(64));
    }

    @Test void publishesNormalizedMetadataAndStateAndUpdatesWithForeignKeys() throws Exception {
        Path db=root.resolve("index.db");
        var store=new DatamartStore(db);
        assertEquals(0,store.snapshot().version());
        store.saveBook(data("123","old"));
        store.saveBook(data("123","new"));
        assertEquals(2,store.snapshot().version());
        assertEquals(1,store.snapshot().booksIndexed());
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+db); var s=c.createStatement()) {
            try (var rs=s.executeQuery("SELECT author_norm,language,year,word_count,content_sha,indexed_at FROM books")) {
                assertTrue(rs.next()); assertEquals("jose alvarez",rs.getString(1));
                assertEquals("es",rs.getString(2)); assertNull(rs.getObject(3)); assertEquals(3,rs.getInt(4));
                assertEquals("a".repeat(64),rs.getString(5)); assertNotNull(rs.getString(6));
            }
            try (var rs=s.executeQuery("SELECT term,tf FROM postings")) {
                assertTrue(rs.next()); assertEquals("new",rs.getString(1)); assertEquals(3,rs.getInt(2)); assertFalse(rs.next());
            }
            try (var rs=s.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rs.next()); }
        }
        store.clearIndex();
        assertEquals(3,store.snapshot().version());
        assertEquals(0,store.countBooks());
    }

    @Test void migratesLegacyDatabaseWithoutLosingPostingsAndOnlyOnce() throws Exception {
        Path db=root.resolve("legacy.db");
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+db); var s=c.createStatement()) {
            s.execute("CREATE TABLE books(book_id TEXT PRIMARY KEY,title TEXT,author TEXT,language TEXT,body_path TEXT)");
            s.execute("CREATE TABLE postings(word TEXT,book_id TEXT,frequency INTEGER,PRIMARY KEY(word,book_id),FOREIGN KEY(book_id) REFERENCES books(book_id))");
            s.execute("CREATE TABLE index_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            s.execute("INSERT INTO books VALUES('123','Title','Unknown','English','old.body.txt')");
            s.execute("INSERT INTO postings VALUES('love','123',7)");
            s.execute("INSERT INTO index_metadata VALUES('last_update','2026-11-04T17:00:00Z')");
        }
        var store=new DatamartStore(db);
        assertEquals(1,store.snapshot().version());
        assertEquals(1,store.countBooks());
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+db); var s=c.createStatement()) {
            try (var rs=s.executeQuery("SELECT author,author_norm,language,word_count,content_sha FROM books")) {
                assertTrue(rs.next()); assertNull(rs.getString(1)); assertNull(rs.getString(2));
                assertEquals("en",rs.getString(3)); assertEquals(7,rs.getInt(4)); assertNull(rs.getString(5));
            }
            try (var rs=s.executeQuery("SELECT term,tf FROM postings")) {
                assertTrue(rs.next()); assertEquals("love",rs.getString(1)); assertEquals(7,rs.getInt(2));
            }
        }
        assertEquals(1,new DatamartStore(db).snapshot().version());
        store.saveBook(data("123","new"));
        assertEquals(2,store.snapshot().version());
    }

    @Test void walReaderKeepsOneSnapshotWhileWriterPublishes() throws Exception {
        Path db=root.resolve("index.db");
        var store=new DatamartStore(db);
        store.saveBook(data("123","love"));
        // SQLite read-only mode, matching the future Search connection.
        try (var reader=DriverManager.getConnection("jdbc:sqlite:"+db.toUri()+"?mode=ro");
             var stmt=reader.createStatement()) {
            stmt.execute("PRAGMA query_only=ON");
            reader.setAutoCommit(false);
            try (var rs=stmt.executeQuery("SELECT version FROM index_state")) { assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); }
            store.saveBook(data("456","love"));
            try (var rs=stmt.executeQuery("SELECT COUNT(*) FROM books")) { assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); }
            try (var rs=stmt.executeQuery("SELECT version,books_indexed FROM index_state")) {
                assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); assertEquals(1,rs.getInt(2));
            }
            reader.commit();
            try (var rs=stmt.executeQuery("SELECT version,books_indexed FROM index_state")) {
                assertTrue(rs.next()); assertEquals(2,rs.getInt(1)); assertEquals(2,rs.getInt(2));
            }
            assertThrows(SQLException.class,()->stmt.executeUpdate("DELETE FROM books"));
        }
    }

    @Test void failedRebuildPreservesVersionAndPublishedContent() throws Exception {
        var store=new DatamartStore(root.resolve("index.db"));
        store.saveBook(data("123","original"));
        var previous=store.snapshot();
        assertThrows(SQLException.class,()->store.rebuildIndex(java.util.List.of(data("456","new"),data("456","duplicate"))));
        assertEquals(previous,store.snapshot());
    }

    @Test void serviceRejectsSameLengthBodyCorruptionWithoutReplacingPublishedIndex() throws Exception {
        Path lake=Files.createDirectories(root.resolve("lake"));
        Path marker=PublishedBookFixture.create(lake,"20261104/17",123,"Title: Test\nLanguage: English\n",
                "love love","2026-11-04T17:00:00Z");
        var service=new IndexingService(new Config(0,lake,root.resolve("index.db"),0));
        assertTrue(service.indexBook("123"));
        var previous=service.getStatus();
        Files.writeString(marker.resolveSibling("123.body.txt"),"hate hate");
        assertThrows(java.io.IOException.class,service::rebuildIndex);
        assertEquals(previous,service.getStatus());
        assertEquals(1,service.indexBooks().failed());
    }

    @Test void eightThreadsUpdatingDifferentBooksPublishEveryBook() throws Exception {
        Path lake=Files.createDirectories(root.resolve("lake")), db=root.resolve("index.db");
        int books=40;
        for (int id=1; id<=books; id++)
            PublishedBookFixture.create(lake,"20261104/17",id,"Title: Book "+id+"\n","love book word"+"x".repeat(id),"2026-11-04T17:00:00Z");
        var service=new IndexingService(new Config(0,lake,db,0));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int id=1; id<=books; id++) {
                String bookId=Integer.toString(id);
                tasks.add(() -> service.indexBook(bookId));
            }
            for (var result : pool.invokeAll(tasks)) assertTrue(result.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(books,service.getStatus().books_indexed());
        assertEquals(books,service.getStatus().index_version());
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();
             var rs=s.executeQuery("SELECT COUNT(*) FROM postings WHERE term='love'")) {
            assertTrue(rs.next()); assertEquals(books,rs.getInt(1));
        }
    }

    @Test void rebuildFailureOnALaterBookKeepsThePublishedIndex() throws Exception {
        var store=new DatamartStore(root.resolve("index.db"));
        store.saveBook(data("123","original"));
        var previous=store.snapshot();
        assertThrows(java.io.IOException.class,()->store.rebuildFrom(java.util.List.of(
                ()->data("456","new"),
                ()->{ throw new java.io.IOException("unreadable"); })));
        assertEquals(previous,store.snapshot());
    }

    @Test void storesBodyPathRelativeToTheDatalake() throws Exception {
        Path lake=Files.createDirectories(root.resolve("lake")), db=root.resolve("index.db");
        PublishedBookFixture.create(lake,"20261104/17",7,"Title: T\n","love book","2026-11-04T17:00:00Z");
        assertTrue(new IndexingService(new Config(0,lake,db,0)).indexBook("7"));
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();
             var rs=s.executeQuery("SELECT body_path FROM books")) {
            assertTrue(rs.next()); assertEquals("20261104/17/7.body.txt",rs.getString(1));
        }
    }

    @Test void serviceUsesTheSameOldestBookForUpdateAndRebuild() throws Exception {
        Path lake=Files.createDirectories(root.resolve("lake")), db=root.resolve("index.db");
        PublishedBookFixture.create(lake,"20261104/17",123,"Title: Old\n","old book","2026-11-04T17:00:00Z");
        PublishedBookFixture.create(lake,"20261105/10",123,"Title: New\n","new book","2026-11-05T10:00:00Z");
        var service=new IndexingService(new Config(0,lake,db,0));
        assertEquals(1,service.indexBooks().indexed());
        assertTrue(service.indexBook("123"));
        assertEquals(1,service.rebuildIndex().books_processed());
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();
             var rs=s.executeQuery("SELECT title FROM books")) {
            assertTrue(rs.next()); assertEquals("Old",rs.getString(1)); assertFalse(rs.next());
        }
    }
}
