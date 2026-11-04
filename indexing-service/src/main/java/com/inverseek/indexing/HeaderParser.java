
package com.inverseek.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeaderParser {

    private static String extractField(String content, String field) {

        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(field) + ":\\s*(.+)$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(content);

        return matcher.find() ? matcher.group(1).trim() : "Unknown";
    }

    public static BookMetadata parse(Path headerPath) throws IOException {

        String content = Files.readString(
                headerPath,
                StandardCharsets.UTF_8
        );

        String title = extractField(content, "Title");
        String author = extractField(content, "Author");
        String language = extractField(content, "Language");

        return new BookMetadata(title, author, language);
    }

    public record BookMetadata(
            String title,
            String author,
            String language
    ) {
    }
}
