package com.inverseek.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a Project Gutenberg plain-text file into header and body.
 *
 * <p>The header is everything before the {@code *** START OF THE PROJECT GUTENBERG EBOOK ... ***}
 * line (it contains Title, Author, Language, Release date...). The body is the text between that
 * line and the {@code *** END OF ...} line. The licence after the END line is dropped.
 */
public final class BookParser {

    private static final Pattern START = Pattern.compile(
            "^\\*\\*\\*\\s*START OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK.*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern END = Pattern.compile(
            "^\\*\\*\\*\\s*END OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK.*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private BookParser() {
    }

    public record SplitBook(String header, String body) {
    }

    public static SplitBook split(int bookId, String raw) {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        text = text.replace("\r\n", "\n");

        Matcher start = START.matcher(text);
        if (!start.find()) {
            throw new InvalidBookException("Book " + bookId + " has no '*** START OF THE PROJECT GUTENBERG EBOOK' marker");
        }
        Matcher end = END.matcher(text);
        int bodyEnd = end.find(start.end()) ? end.start() : text.length();

        String header = text.substring(0, start.start()).strip();
        String body = text.substring(start.end(), bodyEnd).strip();
        if (body.isEmpty()) {
            throw new InvalidBookException("Book " + bookId + " has an empty body");
        }
        return new SplitBook(header + "\n", body + "\n");
    }
}
