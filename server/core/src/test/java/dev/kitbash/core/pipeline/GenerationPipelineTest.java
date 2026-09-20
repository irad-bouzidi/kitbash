package dev.kitbash.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.plan.Caps;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.resolve.Resolver;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.core.workspace.Workspace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Each of §6's seven stages, exercised on its own and then end to end — with no Spring anywhere,
 * which is the property §6 wanted the module boundary for in the first place.
 */
class GenerationPipelineTest {

    private static final Catalog CATALOG = TestCatalogFixture.catalog();

    private static GenerationPipeline pipeline() {
        return GenerationPipeline.over(CATALOG, TestCatalogFixture.content(), new FakeRenderStage());
    }

    private static SelectionEnvelope envelope(Object... optionPairs) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (int i = 0; i < optionPairs.length; i += 2) {
            options.put((String) optionPairs[i], optionPairs[i + 1]);
        }
        return SelectionEnvelope.current(
                "customer-management", options, Map.of("groupId", "com.acme", "packageName", "com.acme.customer"));
    }

    private static SelectionEnvelope standard() {
        return envelope("backend", "backend-spring-java");
    }

    private static String text(Workspace workspace, String path) {
        assertThat(workspace.contains(path)).as("workspace contains %s", path).isTrue();
        return new String(workspace.get(path).content(), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("stage 1 — parse")
    class Parse {

        @Test
        @DisplayName("types the envelope and applies the schema migration")
        void typesTheEnvelope() {
            assertThat(pipeline().parse(standard()).projectName()).isEqualTo("customer-management");
        }

        @Test
        @DisplayName("rejects an unknown recipe id here, before anything is resolved")
        void rejectsUnknownRecipes() {
            // Carrying `backend-spring-jva` as configuration would resolve to a project with no
            // backend at all, and the user would find out from an empty zip.
            assertThatThrownBy(() -> pipeline().parse(envelope("backend", "backend-spring-jva")))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> {
                        var error = ((GenerationException) thrown).error();
                        assertThat(error.code()).isEqualTo(ErrorCode.UNKNOWN_RECIPE);
                        assertThat(error.hint()).contains("backend-spring-java");
                    });
        }

        @Test
        @DisplayName("lets a hyphenated option value through, because some recipe declares it")
        void acceptsDeclaredHyphenatedValues() {
            Catalog withModular = Catalog.of(
                    java.util.List.of(
                            TestCatalogFixture.base(),
                            new dev.kitbash.core.recipe.Recipe(
                                    TestCatalogFixture.BACKEND,
                                    dev.kitbash.core.recipe.RecipeVersion.parse("1.0.0"),
                                    null,
                                    dev.kitbash.core.recipe.RecipeKind.BACKEND,
                                    "Backend",
                                    java.util.Set.of(dev.kitbash.core.recipe.Capability.of("rest-api")),
                                    java.util.Set.of(),
                                    java.util.Set.of(),
                                    java.util.List.of(new dev.kitbash.core.recipe.OptionSpec(
                                            "architecture",
                                            dev.kitbash.core.recipe.OptionType.ENUM,
                                            java.util.List.of("layered", "modular-monolith"),
                                            dev.kitbash.core.selection.OptionValue.text("layered"),
                                            "Architecture",
                                            "help")),
                                    java.util.Set.of(),
                                    java.util.List.of(),
                                    java.util.List.of(),
                                    false)),
                    "sha256:modular");

            assertThat(GenerationPipeline.over(withModular, TestCatalogFixture.content(), new FakeRenderStage())
                            .parse(envelope("architecture", "modular-monolith"))
                            .options())
                    .containsKey("architecture");
        }

        @Test
        @DisplayName("rejects an identifier that would land in a package declaration")
        void rejectsBadIdentifiers() {
            SelectionEnvelope bad =
                    new SelectionEnvelope(1, "customer-management", Map.of(), Map.of("packageName", "com.new.thing"));

            assertThatThrownBy(() -> pipeline().parse(bad))
                    .isInstanceOf(dev.kitbash.core.selection.SelectionValidationException.class)
                    .hasMessageContaining("keyword");
        }
    }

    @Nested
    @DisplayName("stage 2 — validate")
    class Validate {

        @Test
        @DisplayName("runs only stages 1-2, which is what makes it callable on every keystroke")
        void resolvesOnly() {
            Resolution resolution = pipeline().validate(standard());

            assertThat(resolution.valid()).isTrue();
            assertThat(resolution.recipes())
                    .extracting(recipe -> recipe.id().value())
                    .containsExactly("base", "backend-spring-java");
            assertThat(resolution.effectiveOptions())
                    .containsEntry("architecture", dev.kitbash.core.selection.OptionValue.text("layered"));
        }

        @Test
        @DisplayName("returns a parse failure as a payload rather than throwing")
        void returnsParseFailuresAsConflicts() {
            // A wizard calling this on every change wants something to render either way; an
            // exception here would turn a half-typed value into an error response.
            Resolution resolution = pipeline().validate(envelope("backend", "backend-spring-jva"));

            assertThat(resolution.valid()).isFalse();
            assertThat(resolution.firstConflict().code()).isEqualTo(ErrorCode.UNKNOWN_RECIPE);
        }
    }

    @Nested
    @DisplayName("stage 3 — plan")
    class Plan {

        @Test
        @DisplayName("evaluates when, and strips the glob prefix so trees land at the project root")
        void selectsTheRightTree() {
            Resolution layered = Resolver.resolve(CATALOG, pipeline().parse(standard()));

            FilePlan plan = pipeline().plan(layered);

            assertThat(plan.effectiveEntries())
                    .extracting(entry -> entry.path())
                    .contains("src/main/java/{{ packageName | packagePath }}/web/Controller.java.peb")
                    .doesNotContain("src/main/java/{{ packageName | packagePath }}/adapter/in/web/Controller.java.peb");
        }

        @Test
        @DisplayName("a different option value selects a different tree, with no other change")
        void switchesTree() {
            Resolution hexagonal = Resolver.resolve(
                    CATALOG, pipeline().parse(envelope("backend", "backend-spring-java", "architecture", "hexagonal")));

            assertThat(pipeline().plan(hexagonal).effectiveEntries())
                    .extracting(entry -> entry.path())
                    .anyMatch(path -> path.contains("adapter/in/web"));
        }

        @Test
        @DisplayName("a conditional patch is collected only when its condition holds")
        void collectsConditionalPatches() {
            Resolution without = Resolver.resolve(CATALOG, pipeline().parse(standard()));
            Resolution with = Resolver.resolve(
                    CATALOG, pipeline().parse(envelope("backend", "backend-spring-java", "docs", true)));

            assertThat(pipeline().plan(without).patches()).hasSize(1);
            assertThat(pipeline().plan(with).patches()).hasSize(2);
        }

        @Test
        @DisplayName("records every claim on a path, so a patch target is answerable before rendering")
        void recordsClaims() {
            FilePlan plan = pipeline().plan(Resolver.resolve(CATALOG, pipeline().parse(standard())));

            assertThat(plan.produces(".gitignore")).isTrue();
            assertThat(plan.ownerOf(".gitignore")).contains(TestCatalogFixture.BASE);
        }

        @Test
        @DisplayName("caps are enforced before a single byte is rendered")
        void enforcesCaps() {
            GenerationPipeline tiny = new GenerationPipeline(
                    CATALOG, TestCatalogFixture.content(), new FakeRenderStage(), Caps.of(2, 1_000_000, 1_000_000));

            assertThatThrownBy(() -> tiny.generate(standard()))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> {
                        var error = ((GenerationException) thrown).error();
                        assertThat(error.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
                        assertThat(error.message()).contains("file count").contains("2");
                    });
        }

        @Test
        @DisplayName("a per-file cap names the file that tripped it")
        void namesTheOversizedFile() {
            GenerationPipeline tiny = new GenerationPipeline(
                    CATALOG, TestCatalogFixture.content(), new FakeRenderStage(), Caps.of(5_000, 50_000_000, 5));

            assertThatThrownBy(() -> tiny.generate(standard()))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> assertThat(
                                    ((GenerationException) thrown).error().message())
                            .contains("single file size"));
        }
    }

    @Nested
    @DisplayName("stages 4-5 — render and patch")
    class RenderAndPatch {

        @Test
        @DisplayName("preview runs stages 1-5 and stops: no git skeleton, no zip")
        void previewStopsAtFive() {
            Workspace preview = pipeline().preview(standard());

            assertThat(text(preview, "README.md")).isEqualTo("# customer-management\n");
            assertThat(text(preview, ".gitignore")).isEqualTo("# generated\nbuild/\n.gradle/\n");
            assertThat(preview.files().keySet()).noneMatch(path -> path.startsWith(".git/"));
        }

        @Test
        @DisplayName("patches are applied to the rendered tree, in recipe order")
        void patchesApplyAfterRendering() {
            assertThat(text(pipeline().preview(envelope("backend", "backend-spring-java", "docs", true)), ".gitignore"))
                    .isEqualTo("# generated\nbuild/\n.gradle/\ndocs/build/\n");
        }
    }

    @Nested
    @DisplayName("stages 6-7 — post-process and package")
    class PostProcessAndPackage {

        @Test
        @DisplayName("generate produces a repository, not a pile of files")
        void writesAGitSkeleton() {
            GeneratedProject project = pipeline().generate(standard());

            assertThat(project.workspace().contains(".git/HEAD")).isTrue();
            assertThat(project.commitId()).hasSize(40);
        }

        @Test
        @DisplayName("carries the lock and the selection hash, so nothing downstream recomputes them")
        void carriesTheReceipt() {
            GeneratedProject project = pipeline().generate(standard());

            assertThat(project.lock().catalogDigest()).isEqualTo("sha256:fixture");
            assertThat(project.lock().coordinates()).contains("backend-spring-java@1.4.0");
            assertThat(project.selectionHash()).hasSize(64);
        }

        @Test
        @DisplayName("CRLF from a template or a checkout never reaches the zip")
        void normalisesLineEndings() {
            // A zip that changes because somebody's core.autocrlf is on is a cache key that
            // depends on a developer's git config (§4).
            GeneratedProject project = pipeline().generate(standard());

            project.workspace().files().forEach((path, file) -> {
                if (!path.startsWith(".git/")) {
                    assertThat(new String(file.content(), StandardCharsets.UTF_8))
                            .as("%s", path)
                            .doesNotContain("\r");
                }
            });
        }

        @Test
        @DisplayName("the same selection twice produces byte-identical zips")
        void isDeterministicInProcess() throws IOException {
            assertThat(zipOf(standard())).isEqualTo(zipOf(standard()));
        }

        @Test
        @DisplayName("a different selection produces a different zip, so the test above means something")
        void differentSelectionsDiffer() throws IOException {
            assertThat(zipOf(standard()))
                    .isNotEqualTo(zipOf(envelope("backend", "backend-spring-java", "architecture", "hexagonal")));
        }

        private byte[] zipOf(SelectionEnvelope envelope) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pipeline().generate(envelope).streamTo(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("generating from an unresolvable selection raises rather than emitting half a project")
    void refusesToGenerateFromAConflict() {
        // A value outside the enum is caught at resolve; generating anyway would emit a project
        // with whichever tree happened to be the default, which is worse than an error.
        assertThatThrownBy(
                        () -> pipeline().generate(envelope("backend", "backend-spring-java", "architecture", "clean")))
                .isInstanceOf(GenerationException.class)
                .satisfies(thrown -> assertThat(
                                ((GenerationException) thrown).error().code())
                        .isEqualTo(ErrorCode.INVALID_IDENTIFIER));
    }
}
