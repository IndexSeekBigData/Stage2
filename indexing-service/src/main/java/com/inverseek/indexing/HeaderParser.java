package com.inverseek.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class HeaderParser {
    private static final Map<String, String> LANGUAGES = languages();

    private HeaderParser() {}

    private static String extractField(String content, String field) {
        // Horizontal whitespace only: an empty field must not consume the next line.
        var matcher = Pattern.compile("^" + Pattern.quote(field) + ":[ \\t]*([^\\r\\n]*)$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(content);
        return matcher.find() ? nullable(matcher.group(1)) : null;
    }

    public static BookMetadata parse(Path headerPath) throws IOException {
        return parseContent(Files.readString(headerPath, StandardCharsets.UTF_8));
    }

    public static BookMetadata parseContent(String content) {
        return new BookMetadata(extractField(content, "Title"), extractField(content, "Author"),
                languageCode(extractField(content, "Language")));
    }

    static String nullable(String value) {
        if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("Unknown")) return null;
        return value.strip();
    }

    public static String normalizeAuthor(String author) {
        String value = nullable(author);
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    public static String languageCode(String language) {
        String value = nullable(language);
        return value == null ? null : LANGUAGES.get(value.toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> languages() {
        Map<String, String> result = new HashMap<>();
        for (String code : Locale.getISOLanguages()) {
            Locale locale = Locale.forLanguageTag(code);
            result.put(code, code);
            result.put(locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT), code);
            try { result.put(locale.getISO3Language(), code); }
            catch (java.util.MissingResourceException ignored) { /* No ISO3 mapping. */ }
        }
        return Map.copyOf(result);
    }

    public record BookMetadata(String title, String author, String language) {}
}
