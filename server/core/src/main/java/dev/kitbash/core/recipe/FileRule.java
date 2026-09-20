package dev.kitbash.core.recipe;

import java.util.Objects;

/**
 * One {@code files[]} entry from a manifest: a glob under the recipe directory, and the condition
 * under which it contributes (§4).
 *
 * <p>{@code when} is the entire conditional surface of the manifest format, which is why §7 keeps
 * it to a tiny documented expression language rather than letting it grow into a scripting language
 * one convenience at a time. {@code null} means {@code always}.
 */
public record FileRule(String from, String when) {

    public static final String ALWAYS = "always";

    public FileRule {
        Objects.requireNonNull(from, "from");
        if (from.isBlank()) {
            throw new IllegalArgumentException("files[].from must not be blank");
        }
        when = when == null || when.isBlank() ? ALWAYS : when;
    }

    public static FileRule always(String from) {
        return new FileRule(from, ALWAYS);
    }

    public boolean unconditional() {
        return ALWAYS.equals(when);
    }
}
