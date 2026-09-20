package dev.kitbash.core.recipe;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A free-text input the wizard renders: the project name, the group id, the package.
 *
 * <p>Separate from {@link OptionSpec} because the two answer different questions. An option picks
 * between things the catalog offers; a variable is a name only the user can supply. Both carry
 * their label, help and validation rule from the server, so the client renders and validates them
 * without knowing what any of them mean (§8, §9).
 *
 * <p>The pattern is the same rule {@code Identifiers} enforces server-side. Shipping it to the
 * client is a convenience, never the check: §13 treats every input as hostile and validates it
 * again on arrival.
 */
public record VariableSpec(String id, String label, String help, String pattern, String defaultValue) {

    public VariableSpec {
        Objects.requireNonNull(id, "id");
        label = label == null || label.isBlank() ? id : label;
        defaultValue = defaultValue == null ? "" : defaultValue;
        if (help == null || help.isBlank()) {
            throw new IllegalArgumentException("variable '" + id + "' must carry help text");
        }
        if (pattern != null && !pattern.isBlank()) {
            try {
                Pattern.compile(pattern);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "variable '" + id + "' declares a pattern that is not a valid regex: " + e.getMessage(), e);
            }
        }
    }
}
