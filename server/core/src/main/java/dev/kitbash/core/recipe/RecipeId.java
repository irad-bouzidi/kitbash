package dev.kitbash.core.recipe;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A recipe's identity, and — since kitbash-47 — where it came from.
 *
 * <p>Wrapped rather than passed as a {@code String} because recipe ids, capability names and file
 * paths are all strings and all flow through the same methods; the compiler should be the one that
 * notices when two of them are swapped.
 *
 * <h2>Two shapes, and the {@code @} that separates them</h2>
 *
 * <p>A shipped recipe is {@code backend-spring-java}: lowercase kebab, and also its directory name
 * under {@code /recipes}. A contributed one is {@code @platform/backend-spring-java}, and the
 * leading {@code @} is not decoration.
 *
 * <p>§3.6 of the threat model is the reason. A contributed recipe appears in the wizard beside
 * shipped ones, in the same control, with the same styling — so {@code backend-spring-java-v2} is
 * an attack, and a cheap one. The namespace makes the two unconfusable, and
 * {@link #contributed()} is what the four places §10 and §14 make somebody look — the wizard, the
 * lock, the logs and the digest — branch on.
 *
 * <p>Carried in the id itself rather than alongside it, because a provenance flag that travels
 * separately is a flag that gets dropped on the way into a log line. The id cannot be written
 * anywhere without its origin coming too.
 */
public record RecipeId(String value) implements Comparable<RecipeId> {

    /** A shipped recipe: lowercase kebab-case, which is also a legal directory name. */
    private static final String NAME = "[a-z][a-z0-9]*(-[a-z0-9]+)*";

    private static final Pattern SHIPPED = Pattern.compile(NAME);

    /**
     * A contributed recipe: {@code @namespace/name}.
     *
     * <p>The namespace obeys the same rule as the name, so neither can carry a character that
     * would need escaping in a path, a log line, a metric label or a JSON key. A namespace that
     * could contain a {@code /} would be a namespace that could forge a second one.
     */
    private static final Pattern CONTRIBUTED = Pattern.compile("@(" + NAME + ")/(" + NAME + ")");

    public RecipeId {
        Objects.requireNonNull(value, "value");
        if (!SHIPPED.matcher(value).matches() && !CONTRIBUTED.matcher(value).matches()) {
            throw new IllegalArgumentException("recipe id must be lowercase kebab-case, starting with a letter, "
                    + "optionally namespaced as '@namespace/name' for a contributed recipe (got: '" + value + "')");
        }
    }

    public static RecipeId of(String value) {
        return new RecipeId(value);
    }

    /** {@code @platform/audit-log} for namespace {@code platform} and name {@code audit-log}. */
    public static RecipeId contributed(String namespace, String name) {
        return new RecipeId("@" + namespace + "/" + name);
    }

    /**
     * Whether this recipe came from outside the repository.
     *
     * <p>The single question every §6 control asks. It is a property of the id rather than a
     * lookup, so nothing has to hold a catalog to answer it — which matters most in the places
     * that have no catalog to hand, like a log line or a stored lock being read back years later.
     */
    public boolean contributed() {
        return value.charAt(0) == '@';
    }

    /** The namespace of a contributed recipe, or empty for a shipped one. */
    public java.util.Optional<String> namespace() {
        if (!contributed()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value.substring(1, value.indexOf('/')));
    }

    /**
     * The name without its namespace — {@code audit-log} for {@code @platform/audit-log}.
     *
     * <p>For a directory name and nothing else. It is deliberately <b>not</b> what the wizard
     * shows or what a log records: stripping the namespace for display is how a contributed recipe
     * becomes indistinguishable from a shipped one, which is the threat.
     */
    public String name() {
        return contributed() ? value.substring(value.indexOf('/') + 1) : value;
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
