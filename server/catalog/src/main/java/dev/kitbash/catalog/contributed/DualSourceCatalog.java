package dev.kitbash.catalog.contributed;

import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Two halves of one catalog, and a digest that says which is which (§10, kitbash-47 §6.3).
 *
 * <p>§10 kept the catalog in git so a digest corresponded to a commit somebody could check out.
 * Half of it still does, and this is what stops that property degrading quietly into "a digest" —
 * the composed digest is {@code git:<d>+contributed:<d>}, so reading one tells you immediately
 * whether any of the recipes behind it came from outside the repository, and which half moved
 * between two generations.
 *
 * <p>A hash of the concatenation would have been shorter and would have thrown that away. The
 * digest is in every generation lock, in the zip cache key and in the wizard's footer; somebody
 * reading it in a bug report years later should not have to look anything up to know what kind of
 * catalog produced the project.
 */
public final class DualSourceCatalog {

    /** Every catalog has both halves, and an empty contributed half is a fact worth stating. */
    public static final String NO_CONTRIBUTED = "none";

    private DualSourceCatalog() {}

    /**
     * Composes the shipped catalog with the approved contributed recipes.
     *
     * @param contributed recipe to its content hash, as the repository holds it. Only approved,
     *     un-revoked rows belong here — visibility is §47's rule and it is enforced by the query
     *     that produces this map rather than re-decided here, because a second place to decide
     *     "may this be seen" is a second place to get it wrong.
     */
    public static Catalog compose(Catalog shipped, Map<Recipe, String> contributed) {
        if (contributed.isEmpty()) {
            // Not merely an optimisation. A server with no contributed recipes should produce
            // exactly the digest it produced before this feature existed, or every cached zip and
            // every stored lock would be invalidated by a feature nobody had used yet.
            return shipped;
        }

        List<Recipe> all = new ArrayList<>(shipped.recipes());
        contributed.keySet().stream().sorted(Comparator.comparing(Recipe::id)).forEach(all::add);

        return Catalog.of(all, digest(shipped.digest(), contributed), shipped.groups(), shipped.variables());
    }

    /**
     * {@code git:<digest>+contributed:<digest>}.
     *
     * <p>The contributed half is computed the same way {@code CatalogLoader} computes the shipped
     * one — sha256 over the sorted set of {@code (recipeId, version, contentHash)} — so the two
     * are comparable, and a recipe moving from contributed to shipped changes which half it counts
     * in without changing what it hashes to.
     */
    static String digest(String shippedDigest, Map<Recipe, String> contributed) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            Map<String, String> sorted = new TreeMap<>();
            contributed.forEach((recipe, contentHash) ->
                    sorted.put(recipe.id().value(), recipe.version() + "\u0000" + contentHash));
            sorted.forEach((id, versionAndHash) -> {
                sha256.update(id.getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0);
                sha256.update(versionAndHash.getBytes(StandardCharsets.UTF_8));
            });
            return "git:" + shippedDigest + "+contributed:sha256:"
                    + java.util.HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
