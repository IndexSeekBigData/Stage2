package com.inverseek.search;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QueryParserTest {
    final QueryParser parser = new QueryParser(TestIndex.config(Path.of("unused.db")));

    static Map<String, List<String>> params(String... pairs) {
        Map<String, List<String>> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], List.of(pairs[i + 1]));
        return result;
    }

    @Test void defaultsDeduplicatesAndUsesIndexingTokenizer() {
        var query = parser.parse(params("q", "LOVE love a an BOOK 123"));
        assertEquals(List.of("book", "love"), query.terms());
        assertEquals(0, query.offset());
        assertEquals(20, query.limit());
        assertEquals(Map.of(), query.filters());
        assertEquals(Map.of("love", 2, "book", 1), Tokenizer.tokenize("LOVE love a an BOOK 123"));
        assertEquals(List.of("love"), parser.parse(params("q", "love café")).terms());
    }

    @Test void requiresAtLeastOneIndexedTermAndAtMostTenDistinctTerms() {
        for (String q : List.of("", " ", "a an 12", "café", "x".repeat(4097),
                "one two three four five six seven eight nine ten eleven")) {
            var error = assertThrows(InvalidQueryException.class, () -> parser.parse(params("q", q)));
            assertEquals("invalid_query", error.code());
        }
        assertEquals("invalid_query", assertThrows(InvalidQueryException.class, () -> parser.parse(Map.of())).code());
        assertEquals(10, parser.parse(params("q", "one two three four five six seven eight nine ten ten")).terms().size());
    }

    @Test void normalizesFiltersAndIgnoresUnknownParameters() {
        var query = parser.parse(params("q", "love", "author", " JOSÉ ÁLVAREZ ", "language", "EN",
                "year", "1900", "limit", "100", "offset", "2147483647", "future", "value"));
        assertEquals("jose alvarez", query.authorNorm());
        assertEquals("en", query.language());
        assertEquals(1900, query.year());
        assertEquals(100, query.limit());
        assertEquals(Integer.MAX_VALUE, query.offset());
        assertEquals(Map.of("author", "JOSÉ ÁLVAREZ", "language", "en", "year", 1900), query.filters());
    }

    @Test void blankOptionalFiltersAreAbsent() {
        var query = parser.parse(params("q", "love", "author", " ", "language", "", "year", ""));
        assertTrue(query.filters().isEmpty());
    }

    @Test void rejectsEveryInvalidParameterAndNumericOverflow() {
        Map<String, List<String>> invalid = Map.of(
                "limit", List.of("", "0", "-1", "101", "text", "2147483648"),
                "offset", List.of("", "-1", "1.5", "text", "2147483648"),
                "year", List.of("0", "2101", "1.5", "text", "2147483648"),
                "language", List.of("english", "e", "en1", "éé"),
                "author", List.of("a".repeat(101)));
        invalid.forEach((name, values) -> values.forEach(value -> {
            var error = assertThrows(InvalidQueryException.class,
                    () -> parser.parse(params("q", "love", name, value)), name + "=" + value);
            assertEquals("invalid_parameter", error.code());
        }));
    }

    @Test void rejectsRepeatedKnownParametersButIgnoresRepeatedUnknownOnes() {
        var values = params("q", "love");
        values.put("language", List.of("en", "es"));
        assertThrows(InvalidQueryException.class, () -> parser.parse(values));
        values.remove("language");
        values.put("unknown", List.of("one", "two"));
        assertEquals(List.of("love"), parser.parse(values).terms());
    }

    @Test void validatesBookIds() {
        assertEquals(1342, QueryParser.bookId("001342"));
        for (String id : List.of("abc", "0", "-1", "10000001", "999999999999"))
            assertEquals("invalid_book_id", assertThrows(InvalidQueryException.class, () -> QueryParser.bookId(id)).code());
    }
}
