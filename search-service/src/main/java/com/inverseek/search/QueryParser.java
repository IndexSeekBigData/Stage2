package com.inverseek.search;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class QueryParser {
    private final int defaultLimit;
    private final int maxLimit;

    public QueryParser(Config config) {
        defaultLimit = config.defaultLimit();
        maxLimit = config.maxLimit();
    }

    public SearchQuery parse(Map<String, List<String>> parameters) {
        String query = single(parameters, "q");
        if (query == null || query.isBlank())
            throw invalidQuery("q is required and must contain at least one indexed word");
        if (query.length() > 4096) throw invalidQuery("q must not exceed 4096 characters");
        List<String> terms = Tokenizer.tokenize(query).keySet().stream().sorted().toList();
        if (terms.isEmpty()) throw invalidQuery("q must contain an ASCII word of at least 3 letters");
        if (terms.size() > 10) throw invalidQuery("q must contain at most 10 distinct indexed terms");

        String author = optional(single(parameters, "author"));
        if (author != null && author.length() > 100) throw invalidParameter("author exceeds 100 characters");
        String authorNorm = author == null ? null : Normalizer.normalize(author, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        String language = optional(single(parameters, "language"));
        if (language != null) {
            language = language.toLowerCase(Locale.ROOT);
            if (!language.matches("[a-z]{2,3}")) throw invalidParameter("language must have 2 or 3 ASCII letters");
        }
        String yearValue = optional(single(parameters, "year"));
        Integer year = yearValue == null ? null : integer(yearValue, "year", 1000, 2100);
        String offsetValue = single(parameters, "offset");
        int offset = offsetValue == null ? 0 : integer(offsetValue, "offset", 0, Integer.MAX_VALUE);
        String limitValue = single(parameters, "limit");
        int limit = limitValue == null ? defaultLimit : integer(limitValue, "limit", 1, maxLimit);
        return new SearchQuery(query, terms, author, authorNorm, language, year, offset, limit);
    }

    private static String single(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null) return null;
        if (values.size() != 1) throw invalidParameter("Parameter must occur once: " + name);
        return values.get(0);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static int integer(String value, String name, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw invalidParameter(name + " must be an integer between " + min + " and " + max);
        }
    }

    public static int bookId(String value) {
        try {
            int id = Integer.parseInt(value);
            if (id < 1 || id > 10_000_000) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new InvalidQueryException("invalid_book_id", "book_id must be an integer between 1 and 10000000");
        }
    }

    private static InvalidQueryException invalidQuery(String message) {
        return new InvalidQueryException("invalid_query", message);
    }

    private static InvalidQueryException invalidParameter(String message) {
        return new InvalidQueryException("invalid_parameter", message);
    }
}
