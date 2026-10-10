package com.yourcompany.surveyai.call.application.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Reads the number a respondent said from text that is already normalized to lowercase ASCII words
// (Turkish letters folded, punctuation removed). Handles digits and Turkish or English number words,
// including compounds ("on bes" = 15, "bin dokuz yuz seksen bes" = 1985), and score phrasing
// ("10 uzerinden 8", "8 out of 10").
public final class SpokenNumberParser {

    private static final int MAX_DIGITS = 9;

    private static final Map<String, Integer> UNITS = Map.ofEntries(
            Map.entry("sifir", 0), Map.entry("zero", 0),
            Map.entry("bir", 1), Map.entry("one", 1),
            Map.entry("iki", 2), Map.entry("two", 2),
            Map.entry("uc", 3), Map.entry("three", 3),
            Map.entry("dort", 4), Map.entry("four", 4),
            Map.entry("bes", 5), Map.entry("five", 5),
            Map.entry("alti", 6), Map.entry("six", 6),
            Map.entry("yedi", 7), Map.entry("seven", 7),
            Map.entry("sekiz", 8), Map.entry("eight", 8),
            Map.entry("dokuz", 9), Map.entry("nine", 9)
    );

    // Words that cannot be followed by another tens word; English teens behave the same way.
    private static final Map<String, Integer> TENS = Map.ofEntries(
            Map.entry("on", 10), Map.entry("ten", 10),
            Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13),
            Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16),
            Map.entry("seventeen", 17), Map.entry("eighteen", 18), Map.entry("nineteen", 19),
            Map.entry("yirmi", 20), Map.entry("twenty", 20),
            Map.entry("otuz", 30), Map.entry("thirty", 30),
            Map.entry("kirk", 40), Map.entry("forty", 40),
            Map.entry("elli", 50), Map.entry("fifty", 50),
            Map.entry("altmis", 60), Map.entry("sixty", 60),
            Map.entry("yetmis", 70), Map.entry("seventy", 70),
            Map.entry("seksen", 80), Map.entry("eighty", 80),
            Map.entry("doksan", 90), Map.entry("ninety", 90)
    );

    private static final Map<String, Integer> HUNDREDS = Map.of("yuz", 100, "hundred", 100);
    private static final Map<String, Integer> THOUSANDS = Map.of("bin", 1000, "thousand", 1000);

    private SpokenNumberParser() {
    }

    // Returns the number the speaker gave, or null when the text contains none.
    public static Integer parse(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return null;
        }
        List<Object> items = toNumbersAndWords(normalizedText.trim().split("\\s+"));

        for (int index = 0; index < items.size(); index++) {
            Object item = items.get(index);
            // "10 uzerinden 8": the score comes after "uzerinden".
            if ("uzerinden".equals(item) && index > 0 && items.get(index - 1) instanceof Integer
                    && index + 1 < items.size() && items.get(index + 1) instanceof Integer score) {
                return score;
            }
            // "8 out of 10": the score comes before "out of".
            if ("out".equals(item) && index > 0 && items.get(index - 1) instanceof Integer score
                    && index + 2 < items.size() && "of".equals(items.get(index + 1)) && items.get(index + 2) instanceof Integer) {
                return score;
            }
        }
        for (Object item : items) {
            if (item instanceof Integer number) {
                return number;
            }
        }
        return null;
    }

    private static List<Object> toNumbersAndWords(String[] tokens) {
        List<Object> items = new ArrayList<>();
        NumberBuilder builder = null;
        for (int tokenIndex = 0; tokenIndex < tokens.length; tokenIndex++) {
            String token = tokens[tokenIndex];
            // English joins hundreds and tens with "and" ("one hundred and twelve").
            if ("and".equals(token) && builder != null && tokenIndex + 1 < tokens.length) {
                WordClass nextClass = classify(tokens[tokenIndex + 1]);
                if (nextClass != null && builder.accepts(nextClass)) {
                    continue;
                }
            }
            if (token.chars().allMatch(Character::isDigit) && token.length() <= MAX_DIGITS) {
                builder = flush(builder, items);
                items.add(Integer.parseInt(token));
                continue;
            }
            WordClass wordClass = classify(token);
            if (wordClass == null) {
                builder = flush(builder, items);
                items.add(token);
                continue;
            }
            if (builder != null && !builder.accepts(wordClass)) {
                builder = flush(builder, items);
            }
            if (builder == null) {
                builder = new NumberBuilder();
            }
            builder.add(wordClass, valueOf(token, wordClass));
        }
        flush(builder, items);
        dropArticleBeforeNumber(items);
        return items;
    }

    // In "bir dort diyelim" the "bir" is the article "a", not the number one.
    private static void dropArticleBeforeNumber(List<Object> items) {
        for (int index = 0; index + 1 < items.size(); index++) {
            if (items.get(index) instanceof ArticleOne && items.get(index + 1) instanceof Integer) {
                items.remove(index);
                index--;
            }
        }
        items.replaceAll(item -> item instanceof ArticleOne ? 1 : item);
    }

    private static NumberBuilder flush(NumberBuilder builder, List<Object> items) {
        if (builder != null) {
            items.add(builder.isLoneBir() ? new ArticleOne() : builder.value());
        }
        return null;
    }

    private static WordClass classify(String token) {
        if (UNITS.containsKey(token)) {
            return WordClass.UNIT;
        }
        if (TENS.containsKey(token)) {
            return WordClass.TEN;
        }
        if (HUNDREDS.containsKey(token)) {
            return WordClass.HUNDRED;
        }
        if (THOUSANDS.containsKey(token)) {
            return WordClass.THOUSAND;
        }
        return null;
    }

    private static int valueOf(String token, WordClass wordClass) {
        return switch (wordClass) {
            case UNIT -> UNITS.get(token);
            case TEN -> TENS.get(token);
            case HUNDRED -> HUNDREDS.get(token);
            case THOUSAND -> THOUSANDS.get(token);
        };
    }

    private enum WordClass {
        UNIT, TEN, HUNDRED, THOUSAND
    }

    private record ArticleOne() {
    }

    // Builds one number from consecutive words. A word that cannot continue the number (a unit after
    // a unit, a tens word after a unit or tens word) starts a new one, so "bes alti" is 5 then 6.
    private static final class NumberBuilder {

        private int thousands;
        private int group;
        private WordClass last;
        private int wordCount;
        private boolean onlyBir = true;

        boolean accepts(WordClass next) {
            return switch (next) {
                case UNIT -> last != WordClass.UNIT;
                case TEN -> last != WordClass.UNIT && last != WordClass.TEN;
                case HUNDRED -> last != WordClass.TEN && last != WordClass.HUNDRED;
                case THOUSAND -> thousands == 0 && last != WordClass.THOUSAND;
            };
        }

        void add(WordClass wordClass, int value) {
            onlyBir = onlyBir && wordClass == WordClass.UNIT && value == 1 && wordCount == 0;
            switch (wordClass) {
                case UNIT, TEN -> group += value;
                case HUNDRED -> group = (group == 0 ? 1 : group) * value;
                case THOUSAND -> {
                    thousands = (group == 0 ? 1 : group) * value;
                    group = 0;
                }
            }
            last = wordClass;
            wordCount++;
        }

        boolean isLoneBir() {
            return onlyBir && wordCount == 1;
        }

        int value() {
            return thousands + group;
        }
    }
}
