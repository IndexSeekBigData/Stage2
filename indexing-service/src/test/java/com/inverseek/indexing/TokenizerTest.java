
package com.inverseek.indexing;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TokenizerTest {

    @Test
    void shouldCountWordFrequencies() {
        Map<String, Integer> result =
                Tokenizer.tokenize("Data science uses data");

        assertEquals(2, result.get("data"));
        assertEquals(1, result.get("science"));
        assertEquals(1, result.get("uses"));
        assertEquals(3, result.size());
    }

    @Test
    void shouldIgnoreCase() {
        Map<String, Integer> result =
                Tokenizer.tokenize("DATA Data data");

        assertEquals(3, result.get("data"));
    }

    @Test
    void shouldIgnoreShortWords() {
        Map<String, Integer> result =
                Tokenizer.tokenize("a an the book");

        assertFalse(result.containsKey("a"));
        assertFalse(result.containsKey("an"));
        assertEquals(1, result.get("the"));
        assertEquals(1, result.get("book"));
    }

    @Test
    void shouldReturnEmptyMapForEmptyText() {
        Map<String, Integer> result =
                Tokenizer.tokenize("");

        assertTrue(result.isEmpty());
    }
}

