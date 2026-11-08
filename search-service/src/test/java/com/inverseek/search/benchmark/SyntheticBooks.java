package com.inverseek.search.benchmark;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic Project Gutenberg-like books for the microbenchmarks.
 *
 * <p>Microbenchmarks must not depend on the network or on files that differ between machines, so
 * the text is generated from fixed seeds. Words follow a Zipf distribution, as in natural language,
 * over a vocabulary of common English words followed by pseudo-words. The sizes are close to real
 * Gutenberg books: SMALL is a short novel (150 KB), MEDIUM is about Pride and Prejudice (750 KB)
 * and LARGE is about War and Peace (3 MB).
 */
public final class SyntheticBooks {

    public enum Size {
        SMALL(150_000), MEDIUM(750_000), LARGE(3_000_000);

        public final int bytes;

        Size(int bytes) {
            this.bytes = bytes;
        }
    }

    public static final int VOCABULARY_SIZE = 20_000;

    private static final String[] COMMON = ("the of and to in that was his he it with is for as had you not be her on at"
            + " by which have or from this him but all she they were my are me one their so an said them we who would"
            + " been will no when there if more out up into do any your what has man could other than our some very"
            + " time upon about may its only now like little then can should made did us such great before must two"
            + " these see know over much down after first good men well own thought long never life come where most"
            + " same old even way being through people shall without day might came think again away eyes heart love"
            + " house night hand world young father mother whale ship sea captain king queen lady sir war peace land"
            + " river city voice light dark").split(" ");
    private static final String[] SYLLABLES = {"ba", "be", "bi", "bo", "ca", "ce", "co", "da", "de", "di", "do", "fa",
            "fe", "fi", "ga", "ge", "go", "ha", "he", "hi", "ka", "la", "le", "li", "lo", "lu", "ma", "me", "mi", "mo",
            "na", "ne", "ni", "no", "pa", "pe", "pi", "po", "ra", "re", "ri", "ro", "sa", "se", "si", "so", "ta", "te",
            "ti", "to", "va", "ve", "vi", "wa", "we", "ya", "za", "ther", "ing", "tion", "ous", "ment"};
    private static final String[] VOCABULARY = vocabulary();
    private static final double[] CUMULATIVE = zipf(VOCABULARY.length, 1.05);

    private SyntheticBooks() {
    }

    /** Word with the given frequency rank: 0 is the most frequent word. */
    public static String word(int rank) {
        return VOCABULARY[rank];
    }

    /** Rank of a word drawn from the Zipf distribution. */
    public static int sampleRank(Random random) {
        int index = Arrays.binarySearch(CUMULATIVE, random.nextDouble());
        return index >= 0 ? index : Math.min(-index - 1, CUMULATIVE.length - 1);
    }

    /** Gutenberg-style header with Title, Author, Release date and Language fields. */
    public static String header(int bookId, String newline) {
        return String.join(newline,
                "The Project Gutenberg eBook of Synthetic Book " + bookId,
                "",
                "This ebook is for the use of anyone anywhere in the United States and",
                "most other parts of the world at no cost and with almost no restrictions",
                "whatsoever. You may copy it, give it away or re-use it under the terms",
                "of the Project Gutenberg License included with this ebook or online.",
                "",
                "Title: Synthetic Book " + bookId,
                "",
                "Author: Jane Bench",
                "",
                "Release date: January 1, 2000 [eBook #" + bookId + "]",
                "",
                "Language: English",
                "",
                "Credits: InverSeek benchmark generator",
                "", "");
    }

    /** Body of about {@code size.bytes} characters, wrapped at 70 columns like Gutenberg texts. */
    public static String body(int bookId, Size size, String newline) {
        Random random = new Random(1_000_003L * bookId + size.ordinal());
        StringBuilder text = new StringBuilder(size.bytes + 200);
        int column = 0;
        while (text.length() < size.bytes) {
            int sentences = 3 + random.nextInt(6);
            for (int s = 0; s < sentences; s++) {
                int words = 8 + random.nextInt(13);
                for (int w = 0; w < words; w++) {
                    String word = word(sampleRank(random));
                    if (w == 0) word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                    String punctuation = w == words - 1 ? "." : random.nextInt(9) == 0 ? "," : "";
                    String token = word + punctuation;
                    if (column > 0 && column + 1 + token.length() > 70) {
                        text.append(newline);
                        column = 0;
                    } else if (column > 0) {
                        text.append(' ');
                        column++;
                    }
                    text.append(token);
                    column += token.length();
                }
            }
            text.append(newline).append(newline);
            column = 0;
        }
        return text.toString();
    }

    private static String[] vocabulary() {
        Set<String> words = new LinkedHashSet<>(Arrays.asList(COMMON));
        Random random = new Random(42);
        while (words.size() < VOCABULARY_SIZE) {
            int syllables = 2 + random.nextInt(3);
            StringBuilder word = new StringBuilder();
            for (int i = 0; i < syllables; i++) word.append(SYLLABLES[random.nextInt(SYLLABLES.length)]);
            words.add(word.toString());
        }
        return words.toArray(new String[0]);
    }

    private static double[] zipf(int n, double exponent) {
        double[] cumulative = new double[n];
        double sum = 0;
        for (int rank = 0; rank < n; rank++) {
            sum += 1.0 / Math.pow(rank + 1, exponent);
            cumulative[rank] = sum;
        }
        for (int rank = 0; rank < n; rank++) cumulative[rank] /= sum;
        return cumulative;
    }
}
