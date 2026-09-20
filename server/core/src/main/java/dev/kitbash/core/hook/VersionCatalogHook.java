package dev.kitbash.core.hook;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.PatchRule;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The first hook, and the case §4 names first: a Gradle version catalog computed from the union of
 * the selected recipes.
 *
 * <p>This is genuinely awkward as data. Each recipe knows the dependencies it needs, but the
 * catalog is a property of the <i>set</i> — one {@code [versions]} entry per alias no matter how
 * many recipes asked for it, one {@code [libraries]} line each, sorted. Expressing that in a
 * manifest would mean either duplicating the whole catalog in every recipe or inventing a
 * cross-recipe aggregation syntax, which is a programming language with extra steps.
 *
 * <p>So each recipe declares an ordinary {@code addDependency} patch with a {@code versionRef}, and
 * the catalog assembles itself from those declarations. The output is written through two
 * {@code insertAtMarker} ops — the same mechanism any recipe would use — so nothing downstream has
 * to know a hook produced it.
 *
 * <p>The result is meant to be read. A generated {@code libs.versions.toml} that a human cannot
 * follow defeats the point of emitting one, so entries are sorted by alias and a dependency whose
 * version comes from a BOM is emitted without a version reference rather than with a made-up one.
 */
public final class VersionCatalogHook implements RecipeHook {

    public static final RecipeId OWNER = RecipeId.of("build-gradle-kts");
    public static final String TARGET = "gradle/libs.versions.toml";
    public static final String VERSIONS_MARKER = "# kitbash:versions";
    public static final String LIBRARIES_MARKER = "# kitbash:libraries";

    @Override
    public String recipeId() {
        return OWNER.value();
    }

    @Override
    public List<PatchOp> contribute(PlanContext context) {
        // Sorted maps rather than sorted-at-the-end lists: two recipes declaring the same alias is
        // normal — a starter needed by both the backend and a feature — and the catalog has to
        // hold one entry, not two.
        TreeMap<String, String> versions = new TreeMap<>();
        TreeMap<String, String> libraries = new TreeMap<>();

        for (Recipe recipe : context.recipes()) {
            for (PatchRule rule : recipe.patches()) {
                if (rule.op() instanceof PatchOp.AddDependency dependency) {
                    collect(dependency, versions, libraries);
                }
            }
        }

        if (libraries.isEmpty()) {
            return List.of();
        }

        List<PatchOp> ops = new ArrayList<>();
        if (!versions.isEmpty()) {
            ops.add(new PatchOp.InsertAtMarker(
                    OWNER,
                    TARGET,
                    VERSIONS_MARKER,
                    versions.entrySet().stream()
                            .map(entry -> entry.getKey() + " = \"" + entry.getValue() + "\"")
                            .toList()));
        }
        ops.add(new PatchOp.InsertAtMarker(OWNER, TARGET, LIBRARIES_MARKER, List.copyOf(libraries.values())));
        return List.copyOf(ops);
    }

    private static void collect(
            PatchOp.AddDependency dependency, TreeMap<String, String> versions, TreeMap<String, String> libraries) {
        String alias = dependency.versionRef();
        if (alias == null || alias.isBlank()) {
            // A literal coordinate, declared inline in the build file. Not every dependency wants
            // an alias, and forcing one would make the catalog a worse index of what matters.
            return;
        }
        String[] parts = dependency.coordinate().split(":");
        if (parts.length < 2) {
            return;
        }
        String module = parts[0] + ":" + parts[1];
        if (parts.length >= 3) {
            versions.putIfAbsent(alias, parts[2]);
            libraries.putIfAbsent(alias, alias + " = { module = \"" + module + "\", version.ref = \"" + alias + "\" }");
        } else {
            // Version-less: a BOM or a Spring Boot starter manages it. Inventing a version here
            // would pin something upstream deliberately left floating.
            libraries.putIfAbsent(alias, alias + " = { module = \"" + module + "\" }");
        }
    }
}
