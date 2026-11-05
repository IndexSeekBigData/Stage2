package com.inverseek.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SearchQuery(String query, List<String> terms, String author, String authorNorm,
                          String language, Integer year, int offset, int limit) {
    public SearchQuery {
        terms = List.copyOf(terms);
    }

    public Map<String, Object> filters() {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (author != null) filters.put("author", author);
        if (language != null) filters.put("language", language);
        if (year != null) filters.put("year", year);
        return filters;
    }
}
