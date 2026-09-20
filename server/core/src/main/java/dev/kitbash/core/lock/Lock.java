package dev.kitbash.core.lock;

import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeVersion;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The receipt that makes a past generation reproducible: exactly which recipe versions rendered it,
 * against exactly which catalog (§7).
 *
 * <p>This is the reconciliation §7 draws between the two source plans. Presets track latest, so a
 * team's house standard follows framework upgrades instead of freezing on Spring Boot 3.2; every
 * *generation* carries a lock, so any past build can be replayed byte-for-byte. From history you
 * can *Regenerate exactly* (replay this lock) or *Regenerate current* (re-resolve today), and when
 * someone says "this used to work" the answer is a diff of two locks.
 *
 * <p>It is also the part worth keeping: §10 expires zips after 30 days precisely because the lock
 * is small and is where the value lives.
 */
public record Lock(Map<RecipeId, RecipeVersion> recipeVersions, String catalogDigest) {

    public Lock {
        Objects.requireNonNull(catalogDigest, "catalogDigest");
        // A sorted copy, because a lock is compared, diffed and hashed, and map iteration order is
        // not a thing any of those should depend on.
        recipeVersions = Map.copyOf(new TreeMap<>(recipeVersions));
    }

    public static Lock of(Collection<Recipe> recipes, String catalogDigest) {
        return new Lock(recipes.stream().collect(Collectors.toMap(Recipe::id, Recipe::version)), catalogDigest);
    }

    /** {@code backend-spring-java@1.4.0, base@1.0.0} — sorted, stable, readable in a diff. */
    public String coordinates() {
        return recipeVersions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().value() + "@" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    public boolean sameCatalogAs(Lock other) {
        return catalogDigest.equals(other.catalogDigest);
    }
}
