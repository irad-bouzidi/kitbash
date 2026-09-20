package dev.kitbash.core.lock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeVersion;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lock is what makes "regenerate exactly" and "regenerate current" two different buttons (§7),
 * and what keeps a three-month-old generation replayable after its zip has expired (§10).
 */
class LockTest {

    @Test
    @DisplayName("coordinates read in a stable order, so two locks diff cleanly")
    void coordinatesAreSorted() {
        Map<RecipeId, RecipeVersion> recorded = new LinkedHashMap<>();
        recorded.put(RecipeId.of("infra-docker"), RecipeVersion.parse("1.0.0"));
        recorded.put(RecipeId.of("base"), RecipeVersion.parse("2.1.0"));
        recorded.put(RecipeId.of("backend-spring-java"), RecipeVersion.parse("1.4.0"));

        Lock lock = new Lock(recorded, "sha256:abc");

        assertThat(lock.coordinates()).isEqualTo("backend-spring-java@1.4.0, base@2.1.0, infra-docker@1.0.0");
    }

    @Test
    @DisplayName("two locks from the same catalog are recognisable as such")
    void locksKnowTheirCatalog() {
        Lock first = new Lock(Map.of(RecipeId.of("base"), RecipeVersion.parse("1.0.0")), "sha256:abc");
        Lock second = new Lock(Map.of(RecipeId.of("base"), RecipeVersion.parse("1.1.0")), "sha256:abc");
        Lock other = new Lock(Map.of(RecipeId.of("base"), RecipeVersion.parse("1.0.0")), "sha256:def");

        assertThat(first.sameCatalogAs(second)).isTrue();
        assertThat(first.sameCatalogAs(other)).isFalse();
    }

    @Test
    @DisplayName("a lock is a value, so equality answers 'is this the same render?'")
    void locksAreValues() {
        Lock first = new Lock(Map.of(RecipeId.of("base"), RecipeVersion.parse("1.0.0")), "sha256:abc");
        Lock second = new Lock(Map.of(RecipeId.of("base"), RecipeVersion.parse("1.0.0")), "sha256:abc");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
