package dev.kitbash.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.plan.FileEntry;
import dev.kitbash.core.plan.FileMode;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RendererTest {

    private static final RecipeId BACKEND = RecipeId.of("backend-spring-java");

    private static TemplateVariables variables() {
        return TemplateVariables.builder()
                .put("projectName", "customer-management")
                .put("packageName", "com.acme.customer")
                .put("javaVersion", "21")
                .build();
    }

    private static FileEntry template(String path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new FileEntry(path, () -> bytes, FileMode.REGULAR, BACKEND, true, bytes.length);
    }

    private static FileEntry verbatim(String path, byte[] bytes, FileMode mode) {
        return new FileEntry(path, () -> bytes, mode, BACKEND, false, bytes.length);
    }

    private static FilePlan planOf(FileEntry... entries) {
        Map<String, List<RecipeId>> claims = new LinkedHashMap<>();
        for (FileEntry entry : entries) {
            claims.put(entry.path(), List.of(entry.owner()));
        }
        return new FilePlan(List.of(entries), List.<PatchOp>of(), claims);
    }

    private static String text(Workspace workspace, String path) {
        assertThat(workspace.contains(path)).as("workspace contains %s", path).isTrue();
        return new String(workspace.get(path).content(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the path is templated too, so the package name reaches the directory layout")
    void rendersPaths() {
        Workspace workspace = Renderer.render(
                planOf(template(
                        "src/main/java/{{ packageName | packagePath }}/Application.java.peb",
                        "package {{ packageName }};\n")),
                variables());

        assertThat(workspace.files().keySet()).containsExactly("src/main/java/com/acme/customer/Application.java");
        assertThat(text(workspace, "src/main/java/com/acme/customer/Application.java"))
                .isEqualTo("package com.acme.customer;\n");
    }

    @Test
    @DisplayName("a file without .peb is copied through byte for byte, braces and all")
    void copiesNonTemplatesUntouched() {
        // Keeping this literal matters: a gradle wrapper properties file, a shell script or a
        // Kotlin DSL build file can contain {{ }} for its own reasons, and running it through the
        // engine would corrupt it or fail.
        byte[] literal = "plugins { id(\"java\") }\n// {{ not a variable }}\n".getBytes(StandardCharsets.UTF_8);

        Workspace workspace =
                Renderer.render(planOf(verbatim("build.gradle.kts", literal, FileMode.REGULAR)), variables());

        assertThat(workspace.get("build.gradle.kts").content()).isEqualTo(literal);
    }

    @Test
    @DisplayName("the executable bit survives rendering, which is what saves gradlew")
    void keepsFileModes() {
        Workspace workspace = Renderer.render(
                planOf(verbatim("gradlew", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8), FileMode.EXECUTABLE)),
                variables());

        assertThat(workspace.get("gradlew").executable()).isTrue();
        assertThat(workspace.get("gradlew").unixMode()).isEqualTo(0_100755);
    }

    @Test
    @DisplayName("a binary marked as a template is refused, not silently mangled")
    void refusesBinaryTemplates() {
        byte[] binary = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 13};

        assertThatThrownBy(() -> Renderer.render(
                        planOf(new FileEntry("logo.png.peb", () -> binary, FileMode.REGULAR, BACKEND, true, 6)),
                        variables()))
                .isInstanceOf(GenerationException.class)
                .satisfies(thrown -> assertThat(
                                ((GenerationException) thrown).error().code())
                        .isEqualTo(ErrorCode.RENDER_FAILED));
    }

    @Test
    @DisplayName("a rendered path that escapes the project root is refused")
    void refusesEscapingPaths() {
        // The check is on the *rendered* path on purpose: a template can produce a path its author
        // never typed. The full defence, including Windows reserved names, is kitbash-20.
        assertThatThrownBy(() -> Renderer.render(
                        planOf(template("{{ escape }}/pwned.txt.peb", "x")),
                        TemplateVariables.builder().put("escape", "../..").build()))
                .isInstanceOf(GenerationException.class)
                .satisfies(thrown -> assertThat(
                                ((GenerationException) thrown).error().code())
                        .isEqualTo(ErrorCode.PATH_ESCAPE));
    }

    @Test
    @DisplayName("parallel rendering is still byte-identical, run after run")
    void isDeterministicUnderParallelism() {
        List<FileEntry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            entries.add(template("src/file-" + i + ".txt.peb", "{{ projectName }} #" + i + "\n"));
        }
        FilePlan plan = planOf(entries.toArray(new FileEntry[0]));

        Workspace first = Renderer.render(plan, variables());
        Workspace second = Renderer.render(plan, variables());

        assertThat(second.files().keySet())
                .containsExactlyElementsOf(first.files().keySet());
        first.files().forEach((path, file) -> assertThat(second.get(path)).isEqualTo(file));
    }

    @Test
    @DisplayName("a failure in one file names that file, not whichever thread noticed first")
    void reportsTheFailingFile() {
        FilePlan plan = planOf(template("ok.txt.peb", "{{ projectName }}"), template("broken.txt.peb", "{{ nope }}"));

        assertThatThrownBy(() -> Renderer.render(plan, variables()))
                .isInstanceOf(GenerationException.class)
                .satisfies(thrown -> assertThat(
                                ((GenerationException) thrown).error().file())
                        .contains("broken.txt.peb"));
    }

    @Test
    @DisplayName("an empty plan renders to an empty workspace rather than failing")
    void rendersNothing() {
        assertThat(Renderer.render(FilePlan.empty(), variables()).fileCount()).isZero();
    }
}
