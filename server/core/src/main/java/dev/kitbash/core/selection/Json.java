package dev.kitbash.core.selection;

import java.util.Locale;

/**
 * Just enough JSON writing for the canonical selection form.
 *
 * <p>{@code core} depends on the JDK and nothing else (§5, §6), so Jackson is not available here —
 * and that is the right constraint rather than an inconvenience: the canonical form is a hash
 * input, so it must not change when a library changes its escaping or key-ordering defaults. What
 * it emits is pinned by this module's tests.
 */
final class Json {

    private Json() {}

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
