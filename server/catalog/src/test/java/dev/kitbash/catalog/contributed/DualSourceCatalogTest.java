package dev.kitbash.catalog.contributed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The digest is provenance, and provenance is the point (§6.3).
 *
 * <p>The composed digest goes into every generation lock, the zip cache key and the wizard footer.
 * What these tests protect is that reading one tells you what kind of catalog produced a project
 * without looking anything up — and that turning the feature on changed nothing for a server
 * nobody has contributed to.
 */
class DualSourceCatalogTest {

    private static Recipe recipe(String id) {
        return new Recipe(
                RecipeId.of(id),
                RecipeVersion.parse("1.0.0"),
                null,
                RecipeKind.FEATURE,
                id,
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                false,
                null);
    }

    private static final Catalog SHIPPED = Catalog.of(List.of(recipe("backend-spring-java")), "sha256:abc");

    @Test
    @DisplayName("a server with no contributed recipes produces exactly the digest it always did")
    void unchangedWithoutContributions() {
        // Not an optimisation. If turning this feature on moved the digest, every cached zip and
        // every stored lock would be invalidated by a feature nobody had used.
        assertThat(DualSourceCatalog.compose(SHIPPED, Map.of()).digest()).isEqualTo("sha256:abc");
    }

    @Test
    @DisplayName("the digest names both halves, so nobody has to look up where a recipe came from")
    void namesBothHalves() {
        String digest = DualSourceCatalog.compose(SHIPPED, Map.of(recipe("@platform/audit-log"), "sha256:def"))
                .digest();

        assertThat(digest).startsWith("git:sha256:abc+contributed:sha256:");
    }

    @Test
    @DisplayName("a contributed recipe changing content moves the digest")
    void contentMovesIt() {
        String before = DualSourceCatalog.compose(SHIPPED, Map.of(recipe("@platform/audit-log"), "sha256:one"))
                .digest();
        String after = DualSourceCatalog.compose(SHIPPED, Map.of(recipe("@platform/audit-log"), "sha256:two"))
                .digest();

        // The same reason CatalogLoader hashes file bytes: otherwise a zip cached from the old
        // content stays servable after the recipe is revised.
        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("revoking a recipe moves the digest back to what it was before it was approved")
    void revocationIsReversible() {
        String plain = DualSourceCatalog.compose(SHIPPED, Map.of()).digest();
        String withRecipe = DualSourceCatalog.compose(SHIPPED, Map.of(recipe("@platform/audit-log"), "sha256:def"))
                .digest();

        assertThat(withRecipe).isNotEqualTo(plain);
        assertThat(DualSourceCatalog.compose(SHIPPED, Map.of()).digest()).isEqualTo(plain);
    }

    @Test
    @DisplayName("the shipped half is unaffected by what the contributed half contains")
    void halvesAreIndependent() {
        String one = DualSourceCatalog.compose(SHIPPED, Map.of(recipe("@platform/audit-log"), "sha256:def"))
                .digest();
        String two = DualSourceCatalog.compose(SHIPPED, Map.of(recipe("@acme/widgets"), "sha256:def"))
                .digest();

        assertThat(one).isNotEqualTo(two);
        assertThat(one.substring(0, one.indexOf("+")))
                .as("the git half")
                .isEqualTo(two.substring(0, two.indexOf("+")))
                .isEqualTo("git:sha256:abc");
    }

    @Test
    @DisplayName("the composed catalog holds both, and the contributed one is still marked")
    void holdsBoth() {
        Catalog composed = DualSourceCatalog.compose(SHIPPED, Map.of(recipe("@platform/audit-log"), "sha256:def"));

        assertThat(composed.recipes())
                .extracting(r -> r.id().value())
                .containsExactly("@platform/audit-log", "backend-spring-java");
        assertThat(composed.find(RecipeId.of("@platform/audit-log")))
                .get()
                .extracting(r -> r.id().contributed())
                .isEqualTo(true);
    }
}
