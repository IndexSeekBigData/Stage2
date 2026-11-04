package com.inverseek.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookParserTest {

    @Test
    void splitsHeaderAndBodyAndDropsLicence() {
        BookParser.SplitBook book = BookParser.split(1342, TestFixtures.sampleBook());

        assertTrue(book.header().contains("Title: Sample Book"));
        assertTrue(book.header().contains("Author: Jane Tester"));
        assertFalse(book.header().contains("*** START"));
        assertTrue(book.body().startsWith("Chapter 1"));
        assertTrue(book.body().contains("truth universally acknowledged"));
        assertFalse(book.body().contains("END OF THE PROJECT"));
        assertFalse(book.body().contains("General Information"));
    }

    @Test
    void removesByteOrderMarkAndNormalisesWindowsLineEnds() {
        String crlf = "﻿" + TestFixtures.sampleBook().replace("\r\n", "\n").replace("\n", "\r\n");
        BookParser.SplitBook book = BookParser.split(1342, crlf);

        assertFalse(book.header().startsWith("﻿"));
        assertFalse(book.body().contains("\r"));
    }

    @Test
    void acceptsOlderMarkerWording() {
        String raw = "Title: Old\n*** START OF THIS PROJECT GUTENBERG EBOOK OLD ***\nText\n*** END OF THIS PROJECT GUTENBERG EBOOK OLD ***\n";
        BookParser.SplitBook book = BookParser.split(7, raw);

        assertEquals("Title: Old\n", book.header());
        assertEquals("Text\n", book.body());
    }

    @Test
    void bodyRunsToTheEndWhenThereIsNoEndMarker() {
        String raw = "Title: X\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\nLast words";
        assertEquals("Last words\n", BookParser.split(8, raw).body());
    }

    @Test
    void rejectsFilesWithoutStartMarker() {
        assertThrows(InvalidBookException.class, () -> BookParser.split(9, "Just some text without markers"));
    }

    @Test
    void rejectsEmptyBody() {
        String raw = "Title: X\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\n\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\n";
        assertThrows(InvalidBookException.class, () -> BookParser.split(10, raw));
    }
}
