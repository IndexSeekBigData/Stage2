package benchmark_index;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Tokenizer {

    private static final Pattern PATTERN =
            Pattern.compile("\\b[a-zA-Z]{3,}\\b");

    public static Set<String> tokenize(String text) {
        Set<String> words = new HashSet<>();

        Matcher matcher = PATTERN.matcher(
                text.toLowerCase(Locale.ROOT)
        );

        while (matcher.find()) {
            words.add(matcher.group());
        }

        return words;
    }
}
