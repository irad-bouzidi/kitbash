package dev.kitbash.core.recipe;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A recipe's identity, which is also its directory name under {@code /recipes}.
 *
 * <p>Wrapped rather than passed as a {@code String} because recipe ids, capability names and file
 * paths are all strings and all flow through the same methods; the compiler should be the one that
 * notices when two of them are swapped.
 */
public record RecipeId(String value) implements Comparable<RecipeId> {

    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public RecipeId {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "recipe id must be lowercase kebab-case, starting with a letter (got: '" + value + "')");
        }
    }

    public static RecipeId of(String value) {
        return new RecipeId(value);
    }

    @Override
    public int compareTo(RecipeId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
