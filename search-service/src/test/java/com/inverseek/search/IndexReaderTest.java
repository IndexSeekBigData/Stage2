package com.inverseek.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexReaderTest {
    @TempDir Path root;
    Path database;
    IndexReader reader;
    SearchService service;
    QueryParser parser;

    @BeforeEach void prepare() throws Exception {
        database = TestIndex.create(root);
        var config = TestIndex.config(database);
        reader = new IndexReader(config);
        service = new SearchService(reader);
        parser = new QueryParser(config);
    }

    SearchService.Response search(String... parameters) throws Exception {
        return service.search(parser.parse(QueryParserTest.params(parameters)));
    }

    @Test void ranksOneTermAndBreaksTiesByNumericBookId() throws Exception {
        var response = search("q", "love");
        assertEquals(5, response.total());
        assertEquals(16, response.index_version());
        assertEquals(List.of(2, 10, 200, 100, 400), response.results().stream().map(IndexReader.Result::book_id).toList());
        assertEquals(3 * Math.log1p(6.0 / 5), response.results().get(0).score(), 1e-12);
        assertEquals(response.results().get(0).score(), response.results().get(1).score(), 1e-12);
    }

    @Test void multipleTermsAreAndAndDuplicateTermsDoNotChangeScore() throws Exception {
        var response = search("q", "love adventure");
        assertEquals(Set.of(2, 100, 400), new HashSet<>(response.results().stream().map(IndexReader.Result::book_id).toList()));
        assertEquals(search("q", "love").results(), search("q", "LOVE love").results());
        assertEquals(0, search("q", "love missingterm").total());
        assertEquals(0, search("q", "love lonely").total());
        assertEquals(0, search("q", "missingterm").total());
    }

    @Test void filtersIndividuallyAndCombinedWithNormalizedAuthors() throws Exception {
        assertEquals(2, search("q", "love", "author", "AUSTEN").total());
        assertEquals(1, search("q", "love", "author", "JOSE ALVAREZ").total());
        assertEquals(1, search("q", "love", "author", "JOSÉ ÁLVAREZ", "language", "ES", "year", "1900").total());
        assertEquals(3, search("q", "love", "language", "en").total());
        assertEquals(1, search("q", "love", "year", "1900").total());
        assertEquals(0, search("q", "love", "language", "zz").total());
        assertEquals(0, search("q", "love", "language", "en", "year", "1900").total());
    }

    @Test void treatsLikeWildcardsEscapeCharactersAndSqlAsLiteralText() throws Exception {
        for (String author : List.of("%", "_", "!", "100% Real_Writer!"))
            assertEquals(List.of(200), search("q", "love", "author", author).results().stream().map(IndexReader.Result::book_id).toList());
        assertEquals(0, search("q", "love", "author", "' OR 1=1 --").total());
    }

    @Test void totalSurvivesEmptyPageAndPagesVisitEachBookOnce() throws Exception {
        var ids = new ArrayList<Integer>();
        for (int offset = 0; offset < 5; offset += 2) {
            var response = search("q", "love", "offset", Integer.toString(offset), "limit", "2");
            assertEquals(5, response.total());
            ids.addAll(response.results().stream().map(IndexReader.Result::book_id).toList());
        }
        assertEquals(List.of(2, 10, 200, 100, 400), ids);
        var empty = search("q", "love", "offset", "999");
        assertEquals(5, empty.total());
        assertTrue(empty.results().isEmpty());
    }

    @Test void detailsAndFiltersHandleNullsWithoutExposingPrivatePaths() throws Exception {
        var book = service.book(300).orElseThrow();
        assertNull(book.author());
        assertNull(book.language());
        assertNull(book.year());
        assertEquals(1, book.word_count());
        assertTrue(service.book(999).isEmpty());
        var filters = service.filters();
        assertEquals(6, filters.books_indexed());
        assertEquals(16, filters.index_version());
        assertEquals(List.of(new IndexReader.Language("en", 3), new IndexReader.Language("es", 1),
                new IndexReader.Language("fr", 1)), filters.languages());
        assertEquals(new IndexReader.Years(1813, 2000, 4), filters.years());
        assertEquals(new IndexReader.Author("Jane Austen", 2), filters.authors().get(0));
        assertEquals(4, filters.authors().size());
    }

    @Test void capsAuthorsAtOneHundredAndUsesDeterministicTieOrder() throws Exception {
        try (var connection = TestIndex.writer(database); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (int id = 1000; id < 1105; id++)
                TestIndex.addBook(connection, id, "Title", "Author " + id, "en", null, Map.of("love", 1));
            statement.execute("UPDATE index_state SET books_indexed=(SELECT COUNT(*) FROM books),version=17");
            connection.commit();
        }
        var filters = service.filters();
        assertEquals(100, filters.authors().size());
        assertEquals("Jane Austen", filters.authors().get(0).name());
        var rest = filters.authors().subList(1, filters.authors().size()).stream().map(IndexReader.Author::name).toList();
        assertEquals(rest.stream().sorted().toList(), rest);
    }

    @Test void snapshotRemainsConsistentAcrossWriterCommit() throws Exception {
        var query = parser.parse(QueryParserTest.params("q", "love"));
        try (var snapshot = reader.openSnapshot()) {
            var frequencies = snapshot.documentFrequencies(query.terms());
            assertEquals(5, frequencies.get("love"));
            TestIndex.publishExtraBook(database);
            var page = snapshot.search(query, Map.of("love", Math.log1p(6.0 / frequencies.get("love"))));
            assertEquals(16, snapshot.state().version());
            assertEquals(6, snapshot.state().booksIndexed());
            assertEquals(5, page.total());
            assertEquals(5, page.results().size());
            assertEquals(5, snapshot.documentFrequencies(query.terms()).get("love"));
        }
        var response = search("q", "love");
        assertEquals(17, response.index_version());
        assertEquals(6, response.total());
    }

    @Test void neverCreatesMissingDatabaseAndCanRecoverWhenIndexAppears() throws Exception {
        Path absent = root.resolve("later.db");
        var missingReader = new IndexReader(TestIndex.config(absent));
        var exception = assertThrows(IndexUnavailableException.class, missingReader::openSnapshot);
        assertEquals("index_not_ready", exception.code());
        assertFalse(Files.exists(absent));
        Path folder = Files.createDirectory(root.resolve("later"));
        Files.move(TestIndex.create(folder), absent);
        try (var snapshot = missingReader.openSnapshot()) {
            assertEquals(16, snapshot.state().version());
        }
    }

    @Test void rejectsOldFutureAndIncompleteSchemas() throws Exception {
        try (var connection = TestIndex.writer(database); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=0");
            assertEquals("index_incompatible", assertThrows(IndexUnavailableException.class, reader::openSnapshot).code());
            statement.execute("PRAGMA user_version=2");
            assertEquals("index_incompatible", assertThrows(IndexUnavailableException.class, reader::openSnapshot).code());
            statement.execute("PRAGMA user_version=1");
            statement.execute("ALTER TABLE books RENAME COLUMN author_norm TO old_author");
            assertEquals("index_incompatible", assertThrows(IndexUnavailableException.class, reader::openSnapshot).code());
        }
    }

    @Test void handlesEmptyPublishedIndexAndNullYearRange() throws Exception {
        try (var connection = TestIndex.writer(database); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("DELETE FROM postings");
            statement.execute("DELETE FROM books");
            statement.execute("UPDATE index_state SET books_indexed=0,version=17");
            connection.commit();
        }
        assertEquals(0, search("q", "love").total());
        var filters = service.filters();
        assertEquals(new IndexReader.Years(null, null, 0), filters.years());
        assertTrue(filters.authors().isEmpty());
        assertTrue(filters.languages().isEmpty());
    }

    @Test void operationsLeaveDatabaseBytesUnchanged() throws Exception {
        byte[] before = Files.readAllBytes(database);
        search("q", "love");
        service.book(2);
        service.filters();
        service.health();
        assertArrayEquals(before, Files.readAllBytes(database));
    }
}
