package com.bnaqica.documentreader.processors;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reduces a document type name to the key that names are compared by, so "Pay stubs", "Paystub" and "pay-stub" all
 * become {@code paystub}. A name with nothing usable in it, such as "N/A", has no key and never becomes a type.
 */
public final class TypeNameNormalizer {

    /** Keys that mean "no answer" rather than a document type. */
    private static final Set<String> UNUSABLE_KEYS = Set.of("na", "none", "unknown");

    private TypeNameNormalizer() {}

    /**
     * Drops accents, lowercases the name, turns everything that isn't a letter or digit (in any alphabet) into a word
     * break, singularizes each word, and joins the words with no spaces.
     */
    public static Optional<String> key(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String withoutAccents = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String key = Arrays.stream(withoutAccents.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .map(TypeNameNormalizer::singular)
                .collect(Collectors.joining());
        return key.isEmpty() || UNUSABLE_KEYS.contains(key) ? Optional.empty() : Optional.of(key);
    }

    /** The first rule that applies wins: ies → y, sses → ss, then a trailing s goes unless the word ends ss, us or is. */
    private static String singular(String word) {
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("sses")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
