
package com.inverseek.search;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Tokenizer {

    private static final Pattern PATTERN =
            Pattern.compile("\\b[a-zA-Z]{3,}\\b");

    public static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> words = new HashMap<>();

        Matcher matcher = PATTERN.matcher(
                text.toLowerCase(Locale.ROOT)
        );

        while (matcher.find()) {
            String word = matcher.group();
            words.merge(word, 1, Integer::sum);
        }

        return words;
    }
}
