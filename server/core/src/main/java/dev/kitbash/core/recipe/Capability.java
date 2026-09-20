package dev.kitbash.core.recipe;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The glue of the composition model (§4).
 *
 * <p>Recipes never name each other. {@code frontend-react-vite} declares {@code requires:
 * [rest-api]}, and any recipe that {@code provides: [rest-api]} satisfies it — which is what lets
 * the resolver validate a selection structurally instead of against a hand-maintained compatibility
 * matrix, the thing that would have rotted within a quarter.
 */
public record Capability(String name) implements Comparable<Capability> {

    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public Capability {
        Objects.requireNonNull(name, "name");
        if (!PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "capability must be lowercase kebab-case, starting with a letter (got: '" + name + "')");
        }
    }

    public static Capability of(String name) {
        return new Capability(name);
    }

    @Override
    public int compareTo(Capability other) {
        return name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return name;
    }
}
