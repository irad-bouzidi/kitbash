package dev.kitbash.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.plan.FileEntry;
import dev.kitbash.core.plan.FileMode;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * kitbash-48's "done when", which is one sentence: <i>a test that fails if a contributed template
 * is ever rendered by the in-process engine.</i>
 *
 * <p>Asserting that directly is awkward — "which engine ran this string" is not a property of the
 * output. So it is asserted by consequence, twice, and each consequence is one that a broken
 * routing would visibly lose.
 *
 * <p>{@link #refusesRatherThanFallingBack} takes away the sandbox and demands a refusal. If the
 * stage ever fell through to Pebble, the render would <b>succeed</b>, and that success is the
 * failure this test catches. It is the stronger of the two because it needs no confinement to run,
 * so it also guards hosts where the rest of this suite cannot.
 *
 * <p>{@link #contributedTemplatesAreNotEvenLoadable} attacks from the other side: a shipped
 * template tries to {@code include} a contributed one. Routing that merely *ran* contributed
 * templates elsewhere while leaving them in the shared registry would let that succeed.
 */
class ContributedRenderStageTest {

    private static final RecipeId SHIPPED = RecipeId.of("backend-spring-java");
    private static final RecipeId CONTRIBUTED = RecipeId.of("@platform/audit-log");

    private static FileEntry template(RecipeId owner, String path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new FileEntry(path, () -> bytes, FileMode.REGULAR, owner, true, bytes.length);
    }

    private static FilePlan planOf(FileEntry... entries) {
        Map<String, List<RecipeId>> claims = new LinkedHashMap<>();
        for (FileEntry entry : entries) {
            claims.put(entry.path(), List.of(entry.owner()));
        }
        return new FilePlan(List.of(entries), List.<PatchOp>of(), claims);
    }

    private static Recipe recipe(RecipeId id) {
        return new Recipe(
                id,
                RecipeVersion.parse("1.0.0"),
                null,
                RecipeKind.FEATURE,
                id.value(),
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

    private static Resolution resolutionOf(RecipeId... ids) {
        return new Resolution(
                java.util.Arrays.stream(ids)
                        .map(ContributedRenderStageTest::recipe)
                        .toList(),
                Set.of(),
                Map.of(),
                Set.<Capability>of(),
                List.of(),
                List.of());
    }

    private static Selection selection() {
        return Selection.of("billing", Map.of());
    }

    @Test
    @DisplayName("without a sandbox a contributed recipe is refused, never rendered in process")
    void refusesRatherThanFallingBack() {
        ContributedRenderStage stage = new ContributedRenderStage(
                new SandboxedRenderer(Confinement.unavailable("no user namespaces here"), "unused"));

        // A template that would render perfectly well in process. That is the point: if routing
        // broke, this would succeed and nobody would notice until it mattered.
        FilePlan plan = planOf(template(CONTRIBUTED, "src/Audit.java.peb", "class Audit {} // {{ projectName }}"));

        assertThatThrownBy(() -> stage.render(plan, selection(), resolutionOf(CONTRIBUTED)))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(thrown -> ((SandboxRefusedException) thrown).code())
                .isEqualTo("SANDBOX_UNAVAILABLE");
    }

    @Test
    @DisplayName("a shipped recipe still renders in process when no contributed one is present")
    void shippedRecipesCostNothing() {
        // The common case has to stay free: no subprocess, and no confinement required. An
        // unavailable sandbox must not stop an ordinary generation.
        ContributedRenderStage stage = new ContributedRenderStage(
                new SandboxedRenderer(Confinement.unavailable("no user namespaces here"), "unused"));

        Workspace workspace = stage.render(
                planOf(template(SHIPPED, "README.md.peb", "# {{ projectName }}")), selection(), resolutionOf(SHIPPED));

        assertThat(new String(workspace.get("README.md").content(), StandardCharsets.UTF_8))
                .isEqualTo("# billing");
    }

    @Test
    @DisplayName("a contributed recipe may not carry patches, because their content is templated too")
    void refusesContributedPatches() {
        // Easy to miss: a mergeYaml body or an appendLines entry is a template, and rendering one
        // in process would leave a hole exactly where somebody would look for it. Refused loudly
        // rather than quietly rendered.
        ContributedRenderStage stage = new ContributedRenderStage(
                new SandboxedRenderer(Confinement.unavailable("no user namespaces here"), "unused"));

        List<PatchOp> ops = List.of(new PatchOp.AppendLines(CONTRIBUTED, ".gitignore", List.of("{{ projectName }}/")));

        assertThatThrownBy(() -> stage.renderPatches(ops, selection(), resolutionOf(CONTRIBUTED)))
                .hasMessageContaining("will not render an untrusted template in process");
    }

    @Test
    @DisplayName("a contributed template is not in the registry, so nothing can include it")
    void contributedTemplatesAreNotEvenLoadable() {
        // Renderer-level, because this is Renderer's guarantee: an entry whose output was supplied
        // from outside is left out of the in-process registry. Routing that ran contributed
        // templates elsewhere but left them loadable would pass every other test in this file.
        FileEntry contributed = template(CONTRIBUTED, "src/Audit.java.peb", "class Audit {}");
        FileEntry attacker = template(
                SHIPPED,
                "README.md.peb",
                "{% include '" + dev.kitbash.render.Renderer.bodyTemplateName(contributed) + "' %}");

        assertThatThrownBy(() -> dev.kitbash.render.Renderer.render(
                        planOf(attacker, contributed),
                        dev.kitbash.render.TemplateVariables.builder()
                                .put("projectName", "billing")
                                .build(),
                        Map.of(),
                        Map.of(
                                dev.kitbash.render.Renderer.pathTemplateName(contributed), "src/Audit.java",
                                dev.kitbash.render.Renderer.bodyTemplateName(contributed), "class Audit {}")))
                .as("a shipped template reached a contributed one")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("a project says in its README which recipes were not shipped with the generator")
    void disclosesContributedRecipes() {
        Confinement confinement = Confinement.probe();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                confinement.isAvailable(), "needs unprivileged user namespaces: " + confinement.reason());

        ContributedRenderStage stage = new ContributedRenderStage(
                new SandboxedRenderer(confinement, System.getProperty("kitbash.sandbox.classpath")));

        Workspace workspace = stage.render(
                planOf(
                        template(SHIPPED, "README.md.peb", "# {{ projectName }}\n\n<!-- kitbash:stack -->\n"),
                        template(CONTRIBUTED, "src/Audit.java.peb", "// audit")),
                selection(),
                resolutionOf(SHIPPED, CONTRIBUTED));

        String readme = new String(workspace.get("README.md").content(), StandardCharsets.UTF_8);
        assertThat(readme).contains("Contributed recipes").contains("@platform/audit-log");
        // Written by the generator, so a recipe cannot word it, shorten it or leave it out — and
        // the marker survives for the recipes that legitimately insert at it.
        assertThat(readme).contains("<!-- kitbash:stack -->");
    }

    @Test
    @DisplayName("a project with no contributed recipes says nothing about them")
    void saysNothingWhenThereIsNothingToSay() {
        ContributedRenderStage stage =
                new ContributedRenderStage(new SandboxedRenderer(Confinement.unavailable("unused"), "unused"));

        Workspace workspace = stage.render(
                planOf(template(SHIPPED, "README.md.peb", "# {{ projectName }}\n\n<!-- kitbash:stack -->\n")),
                selection(),
                resolutionOf(SHIPPED));

        assertThat(new String(workspace.get("README.md").content(), StandardCharsets.UTF_8))
                .doesNotContain("Contributed recipes");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("with a sandbox, a contributed recipe really does render")
    void rendersThroughTheSandbox() {
        Confinement confinement = Confinement.probe();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                confinement.isAvailable(), "needs unprivileged user namespaces: " + confinement.reason());

        ContributedRenderStage stage = new ContributedRenderStage(
                new SandboxedRenderer(confinement, System.getProperty("kitbash.sandbox.classpath")));

        Workspace workspace = stage.render(
                planOf(
                        template(SHIPPED, "README.md.peb", "# {{ projectName }}"),
                        template(CONTRIBUTED, "src/Audit.java.peb", "// audit for {{ projectName }}")),
                selection(),
                resolutionOf(SHIPPED, CONTRIBUTED));

        // Both halves land in one workspace, which is the whole point of merging rather than
        // generating twice.
        assertThat(new String(workspace.get("README.md").content(), StandardCharsets.UTF_8))
                .isEqualTo("# billing");
        assertThat(new String(workspace.get("src/Audit.java").content(), StandardCharsets.UTF_8))
                .isEqualTo("// audit for billing");
    }
}
