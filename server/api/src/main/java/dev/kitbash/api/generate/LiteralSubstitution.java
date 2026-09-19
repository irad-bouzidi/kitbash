package dev.kitbash.api.generate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PHASE-0 SCAFFOLDING — deleted by kitbash-13, which replaces it with Pebble rendering over
 * recipes. Nothing outside this package should depend on it.
 *
 * <p>Replaces the reference project's known literals in a single left-to-right pass.
 *
 * <p>The single pass is the whole point. Running a sequence of {@code String.replace} calls lets a
 * later rule rewrite an earlier rule's output: with {@code groupId = com.demo}, replacing
 * {@code com.example → com.demo} and then {@code demo → my-service} produces {@code com.my-service},
 * which is not a package name. Scanning once and consuming the matched text makes each rule apply
 * to the input only.
 *
 * <p>Rules are tried in insertion order at each position, so longer literals must be registered
 * before any literal that is a prefix of them.
 */
final class LiteralSubstitution {

    private final Map<String, String> rules = new LinkedHashMap<>();

    LiteralSubstitution rule(String literal, String replacement) {
        if (literal.isEmpty()) {
            throw new IllegalArgumentException("a substitution literal must not be empty");
        }
        rules.put(literal, replacement);
        return this;
    }

    String apply(String content) {
        StringBuilder out = new StringBuilder(content.length());
        int position = 0;
        while (position < content.length()) {
            int matched = matchAt(content, position, out);
            if (matched > 0) {
                position += matched;
            } else {
                out.append(content.charAt(position));
                position++;
            }
        }
        return out.toString();
    }

    /** Appends the replacement for the first rule matching at {@code position}; returns its length. */
    private int matchAt(String content, int position, StringBuilder out) {
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            if (content.startsWith(rule.getKey(), position)) {
                out.append(rule.getValue());
                return rule.getKey().length();
            }
        }
        return 0;
    }
}
