package dev.kitbash.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The name conversions every recipe needs, in one place with one word-splitting rule.
 *
 * <p>A generator writes the same name into a package declaration, a class name, a directory, an npm
 * script and a compose service, each with a different convention. Recipes doing that conversion
 * inline is how {@code customerManagement} ends up as a directory in one template and {@code
 * customer-management} in another — so it happens here, once, and the filters in {@link
 * KitbashFilters} are thin wrappers over these.
 */
final class Naming {

    private Naming() {}

    /** {@code com.acme.customer} → {@code com/acme/customer}, for source tree layout. */
    static String packagePath(String value) {
        return value == null ? "" : value.replace('.', '/');
    }

    static String camel(String value) {
        List<String> words = words(value);
        if (words.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(words.get(0));
        words.subList(1, words.size()).forEach(word -> out.append(capitalize(word)));
        return out.toString();
    }

    static String pascal(String value) {
        StringBuilder out = new StringBuilder();
        words(value).forEach(word -> out.append(capitalize(word)));
        return out.toString();
    }

    static String kebab(String value) {
        return String.join("-", words(value));
    }

    static String snake(String value) {
        return String.join("_", words(value));
    }

    /**
     * Splits on every separator a real name might use — hyphen, underscore, space, dot — and on a
     * camel-case hump, so the filters are total: any of the five forms converts to any other.
     */
    private static List<String> words(String value) {
        List<String> words = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return words;
        }
        StringBuilder current = new StringBuilder();
        char[] characters = value.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            char c = characters[i];
            if (c == '-' || c == '_' || c == ' ' || c == '.') {
                flush(words, current);
                continue;
            }
            boolean startsHump = Character.isUpperCase(c)
                    && i > 0
                    && (Character.isLowerCase(characters[i - 1])
                            || Character.isDigit(characters[i - 1])
                            // ACRONYMFollowed -> ACRONYM, Followed
                            || (i + 1 < characters.length && Character.isLowerCase(characters[i + 1])));
            if (startsHump) {
                flush(words, current);
            }
            current.append(Character.toLowerCase(c));
        }
        flush(words, current);
        return words;
    }

    private static void flush(List<String> words, StringBuilder current) {
        if (current.length() > 0) {
            words.add(current.toString());
            current.setLength(0);
        }
    }

    private static String capitalize(String word) {
        return word.isEmpty()
                ? word
                : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT);
    }
}
