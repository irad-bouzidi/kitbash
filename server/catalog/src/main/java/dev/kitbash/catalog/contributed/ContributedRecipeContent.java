package dev.kitbash.catalog.contributed;

import dev.kitbash.core.plan.RecipeContent;
import dev.kitbash.core.recipe.RecipeId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A contributed recipe's {@code files/} tree, which is a column rather than a directory
 * (kitbash-48).
 *
 * <p>{@link RecipeContent} was an interface from the start so that {@code core} could plan without
 * knowing where bytes come from — its own comment says <i>on disk, on a classpath or in a test's
 * map</i>. This is the fourth, and it needed no change to the interface, which is the clearest
 * evidence the boundary was drawn in the right place.
 *
 * <p>Composed with the git one rather than replacing it: {@link #of} takes both and asks each in
 * turn. A recipe id belongs to exactly one half — the git loader refuses a namespaced id and only
 * namespaced ids are stored here — so there is no precedence question to get wrong.
 */
public final class ContributedRecipeContent implements RecipeContent {

    private final Map<RecipeId, Map<String, String>> files;

    private ContributedRecipeContent(Map<RecipeId, Map<String, String>> files) {
        this.files = files;
    }

    /** @param files recipe id to its path-to-content map, as the {@code content} column holds it */
    public static ContributedRecipeContent of(Map<RecipeId, Map<String, String>> files) {
        Map<RecipeId, Map<String, String>> copy = new TreeMap<>();
        files.forEach((id, tree) -> copy.put(id, new TreeMap<>(tree)));
        return new ContributedRecipeContent(Map.copyOf(copy));
    }

    /** The whole catalog's content, git half and contributed half, as one. */
    public static RecipeContent composed(RecipeContent shipped, RecipeContent contributed) {
        return (recipeId, glob) -> recipeId.contributed()
                ? contributed.filesMatching(recipeId, glob)
                : shipped.filesMatching(recipeId, glob);
    }

    @Override
    public List<RecipeFile> filesMatching(RecipeId recipeId, String glob) {
        Map<String, String> tree = files.get(recipeId);
        if (tree == null) {
            return List.of();
        }

        // The same matcher CatalogLoader uses on the git half, so a glob means one thing across
        // the whole catalog. Two implementations of "does this pattern match that path" is a
        // disagreement waiting for the first `**` somebody writes.
        java.nio.file.PathMatcher matcher =
                java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<RecipeFile> matched = new ArrayList<>();
        // A TreeMap, so iteration is path order. §4's determinism depends on the plan seeing
        // files in the same order every time, and a hash-ordered map here would be a
        // byte-identical guarantee that quietly holds only most of the time.
        tree.forEach((path, body) -> {
            if (!matcher.matches(java.nio.file.Path.of(path))) {
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            // Never executable. A contributed recipe cannot ship a launcher, a shell script or
            // anything else the generated project's own build would run — and a mode bit is a
            // cheap thing to grant and an awkward one to take back.
            matched.add(new RecipeFile(path, () -> bytes, false, bytes.length));
        });
        return List.copyOf(matched);
    }
}
