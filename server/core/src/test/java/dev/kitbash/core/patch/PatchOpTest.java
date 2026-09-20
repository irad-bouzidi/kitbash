package dev.kitbash.core.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.recipe.RecipeId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exhaustiveness guarantee, demonstrated rather than asserted in a comment.
 *
 * <p>{@link #describe} is the shape every applier takes: a pattern-matching switch over the sealed
 * {@code PatchOp} with <b>no default branch</b>. Adding a ninth operation breaks compilation here
 * and in every applier, which is the property {@code kitbash-6} exists to buy. The test below is
 * the belt to that braces: it fails if someone adds an op and reaches for a default branch to keep
 * the build green.
 */
class PatchOpTest {

    private static final RecipeId OWNER = RecipeId.of("backend-spring-java");

    /** Exhaustive over the §4 table. No default branch — deliberately, permanently. */
    private static String describe(PatchOp op) {
        return switch (op) {
            case PatchOp.AddDependency value -> "dependency " + value.coordinate();
            case PatchOp.MergeYaml value -> "yaml " + value.content().keySet();
            case PatchOp.MergeJson value -> "json " + value.content().keySet();
            case PatchOp.AddScript value -> "script " + value.name();
            case PatchOp.InsertAtMarker value -> "marker " + value.marker();
            case PatchOp.AppendLines value -> "lines " + value.lines().size();
            case PatchOp.AddEnvVar value -> "env " + value.name();
            case PatchOp.AddComposeService value -> "service " + value.serviceName();
        };
    }

    private static List<PatchOp> oneOfEach() {
        return List.of(
                new PatchOp.AddDependency(OWNER, "build.gradle.kts", "implementation", "org.example:thing", "thing"),
                new PatchOp.MergeYaml(OWNER, "src/main/resources/application.yaml", Map.of("spring", Map.of())),
                new PatchOp.MergeJson(OWNER, "package.json", Map.of("type", "module")),
                new PatchOp.AddScript(OWNER, "package.json", "dev", "vite"),
                new PatchOp.InsertAtMarker(OWNER, "Application.java", "// kitbash:imports", List.of("import a.B;")),
                new PatchOp.AppendLines(OWNER, ".gitignore", List.of("build/")),
                new PatchOp.AddEnvVar(OWNER, ".env.example", "compose.yaml", "app", "PORT", "8080", "the http port"),
                new PatchOp.AddComposeService(OWNER, "compose.yaml", "db", Map.of("image", "postgres:16"), List.of()));
    }

    @Test
    @DisplayName("every op in the §4 table is handled by a switch with no default branch")
    void switchIsExhaustive() {
        assertThat(oneOfEach()).map(PatchOpTest::describe).doesNotContainNull().hasSize(8);
    }

    @Test
    @DisplayName("the sealed hierarchy holds exactly the eight operations §4 permits")
    void hierarchyIsClosedAtEight() {
        // §4 caps the set on purpose: a ninth op is a design discussion, not a quiet addition.
        // Adding one without updating every applier will not compile; adding one *with* a default
        // branch somewhere would compile, and this is what catches that.
        assertThat(PatchOp.class.getPermittedSubclasses()).hasSize(8);
    }

    @Test
    @DisplayName("every op names the recipe that owns it and the file it targets (§4, §14)")
    void everyOpNamesOwnerAndTarget() {
        assertThat(oneOfEach()).allSatisfy(op -> {
            assertThat(op.owner()).isEqualTo(OWNER);
            assertThat(op.target()).isNotBlank();
            assertThat(op.operation()).isNotBlank();
        });
    }

    @Test
    @DisplayName("an op without a target is refused at construction, not at apply time")
    void refusesBlankTarget() {
        assertThatThrownBy(() -> new PatchOp.AppendLines(OWNER, "  ", List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
    }

    @Test
    @DisplayName("ops are value objects, so two identical patches dedupe")
    void opsAreValueObjects() {
        PatchOp first = new PatchOp.AppendLines(OWNER, ".gitignore", List.of("build/"));
        PatchOp second = new PatchOp.AppendLines(OWNER, ".gitignore", List.of("build/"));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
