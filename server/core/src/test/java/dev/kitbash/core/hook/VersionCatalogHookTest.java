package dev.kitbash.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.patch.PatchApplier;
import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The catalog is a property of the selected <i>set</i>, not of any one recipe, which is exactly why
 * it needed a hook rather than a manifest feature (§4).
 */
class VersionCatalogHookTest {

    private static final VersionCatalogHook HOOK = new VersionCatalogHook();

    private static PlanContext context() {
        return new PlanContext(
                List.of(TestRecipes.base(), TestRecipes.gradle(), TestRecipes.backend()),
                Map.of(),
                Map.of(),
                Set.of(Capability.of("build-tool")));
    }

    private static Workspace skeleton() {
        Workspace workspace = new Workspace();
        workspace.putText(
                VersionCatalogHook.TARGET,
                """
                # Single source of truth for every dependency coordinate in this project.
                [versions]
                # kitbash:versions

                [libraries]
                # kitbash:libraries
                """);
        return workspace;
    }

    private static String applyAndRead(PlanContext context) {
        Workspace workspace = skeleton();
        PatchApplier.apply(workspace, HOOK.contribute(context));
        return new String(workspace.get(VersionCatalogHook.TARGET).content(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("assembles one entry per alias from the union of the selected recipes")
    void assemblesFromTheUnion() {
        String toml = applyAndRead(context());

        assertThat(toml)
                .contains("assertj-core = \"3.27.3\"")
                .contains("assertj-core = { module = \"org.assertj:assertj-core\", version.ref = \"assertj-core\" }")
                .contains(
                        "spring-boot-starter-web = { module = \"org.springframework.boot:spring-boot-starter-web\" }");
    }

    @Test
    @DisplayName("two recipes asking for the same alias produce one entry, not two")
    void deduplicatesAcrossRecipes() {
        // `build-gradle-kts` and `backend-spring-java` both declare assertj. A catalog with the
        // alias twice is not a valid catalog, and this is the case a per-recipe manifest field
        // could not have handled — the aggregation is the whole reason this is a hook.
        String toml = applyAndRead(context());

        assertThat(toml.split("assertj-core = \"3\\.27\\.3\"", -1)).hasSize(2);
    }

    @Test
    @DisplayName("a dependency whose version comes from a BOM is emitted without a version ref")
    void leavesBomManagedVersionsAlone() {
        // Inventing a version here would pin something upstream deliberately left floating, and
        // the pin would then rot silently while the BOM moved.
        assertThat(applyAndRead(context()))
                .contains("spring-boot-starter-web = { module = \"org.springframework.boot:spring-boot-starter-web\" }")
                .doesNotContain("spring-boot-starter-web = \"");
    }

    @Test
    @DisplayName("a dependency declared without an alias stays out of the catalog")
    void ignoresInlineDependencies() {
        assertThat(applyAndRead(context())).doesNotContain("inline-only");
    }

    @Test
    @DisplayName("entries are sorted, so the file is reviewable and the output deterministic")
    void sortsEntries() {
        String toml = applyAndRead(context());

        assertThat(toml.indexOf("assertj-core = { module"))
                .isLessThan(toml.indexOf("spring-boot-starter-web = { module"));
    }

    @Test
    @DisplayName("its output is ordinary patch ops, so it is idempotent like any other")
    void isIdempotent() {
        Workspace workspace = skeleton();
        List<PatchOp> ops = HOOK.contribute(context());

        PatchApplier.apply(workspace, ops);
        String once = new String(workspace.get(VersionCatalogHook.TARGET).content(), StandardCharsets.UTF_8);
        PatchApplier.apply(workspace, ops);

        assertThat(new String(workspace.get(VersionCatalogHook.TARGET).content(), StandardCharsets.UTF_8))
                .isEqualTo(once);
    }

    @Test
    @DisplayName("contributes nothing when no selected recipe declares an alias")
    void contributesNothingWhenThereIsNothingToSay() {
        PlanContext bare = new PlanContext(List.of(TestRecipes.base()), Map.of(), Map.of(), Set.of());

        assertThat(HOOK.contribute(bare)).isEmpty();
    }
}
