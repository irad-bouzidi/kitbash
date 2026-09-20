package dev.kitbash.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.RecipeId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FilePlanTest {

    private static final RecipeId BASE = RecipeId.of("base");
    private static final RecipeId BACKEND = RecipeId.of("backend-spring-java");

    @Test
    @DisplayName("a later recipe overrides an earlier one's file, and the plan remembers both claims")
    void lastWriterWinsButTheOverrideIsVisible() {
        FilePlan plan = new FilePlan(
                List.of(entry(BASE, "README.md", "from base"), entry(BACKEND, "README.md", "from backend")),
                List.of(),
                Map.of("README.md", List.of(BASE, BACKEND)));

        assertThat(plan.effectiveEntries()).hasSize(1);
        assertThat(new String(plan.effectiveEntries().get(0).read(), StandardCharsets.UTF_8))
                .isEqualTo("from backend");
        // An override is legitimate — an architecture variant replacing a default source file is
        // exactly that — but a silent one is how two recipes end up fighting over a shared file.
        assertThat(plan.claims().get("README.md")).containsExactly(BASE, BACKEND);
        assertThat(plan.ownerOf("README.md")).contains(BACKEND);
    }

    @Test
    @DisplayName("the plan knows whether a patch target will exist, before anything is rendered")
    void producesAnswersPatchTargetMissing() {
        FilePlan plan = new FilePlan(
                List.of(entry(BASE, ".gitignore", "build/")),
                List.of(new PatchOp.AppendLines(BACKEND, ".gitignore", List.of(".gradle/"))),
                Map.of(".gitignore", List.of(BASE)));

        assertThat(plan.produces(".gitignore")).isTrue();
        assertThat(plan.produces("package.json")).isFalse();
    }

    @Test
    @DisplayName("caps are computed from declared sizes, so no body has to be read to enforce them")
    void sizesAreKnownWithoutRendering() {
        FilePlan plan = new FilePlan(
                List.of(entry(BASE, "a.txt", "aaa"), entry(BASE, "b.txt", "bbbb")),
                List.of(),
                Map.of("a.txt", List.of(BASE), "b.txt", List.of(BASE)));

        assertThat(plan.fileCount()).isEqualTo(2);
        assertThat(plan.declaredBytes()).isEqualTo(7);
    }

    @Test
    @DisplayName("an executable entry keeps its mode, which is what saves gradlew")
    void modesSurvive() {
        FileEntry wrapper = new FileEntry("gradlew", () -> new byte[] {1}, FileMode.EXECUTABLE, BASE, false, 1);

        assertThat(wrapper.mode().unixMode()).isEqualTo(0_100755);
        assertThat(wrapper.at("nested/gradlew").mode()).isEqualTo(FileMode.EXECUTABLE);
    }

    private static FileEntry entry(RecipeId owner, String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new FileEntry(path, () -> bytes, FileMode.REGULAR, owner, true, bytes.length);
    }
}
