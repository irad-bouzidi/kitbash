package dev.kitbash.core.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.workspace.Workspace;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The same questions, asked of both build files (§28).
 *
 * <p>§18 deferred Maven out of phase 1 on the grounds that it doubles every JVM backend's
 * dependency patches. §28 is the test of whether that doubling was inherent or accidental, and the
 * answer is written down here: one op, one declaration per dependency, two formats. If this file
 * ever needs a case that applies to only one of them, {@code addDependency} has stopped being a
 * format-aware operation and become a Gradle-shaped one wearing a general name.
 */
class AddDependencyParityTest {

    private static final RecipeId OWNER = RecipeId.of("backend-spring-java");

    private static final String GRADLE =
            """
            plugins {
                java
            }

            dependencies {
            }
            """;

    private static final String POM =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>0.0.1-SNAPSHOT</version>
              <dependencies>
              </dependencies>
            </project>
            """;

    /** One project per build file, each holding nothing but that file. */
    static Stream<org.junit.jupiter.params.provider.Arguments> buildFiles() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("build.gradle.kts", GRADLE),
                org.junit.jupiter.params.provider.Arguments.of("pom.xml", POM));
    }

    private static Workspace projectWith(String path, String content) {
        Workspace workspace = new Workspace();
        workspace.putText(path, content);
        return workspace;
    }

    private static PatchOp.AddDependency dependency(String target, String configuration, String coordinate) {
        return new PatchOp.AddDependency(OWNER, target, configuration, coordinate, null);
    }

    @Nested
    @DisplayName("parity")
    class Parity {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.kitbash.core.patch.AddDependencyParityTest#buildFiles")
        @DisplayName("the dependency lands in the file, whichever format it is")
        void addsTheDependency(String path, String content) {
            Workspace workspace = projectWith(path, content);

            PatchApplier.applyOne(
                    workspace, dependency(".", "implementation", "org.springframework.boot:spring-boot-starter-web"));

            assertThat(textOf(workspace, path)).contains("spring-boot-starter-web");
        }

        /**
         * The property that makes a patch engine usable: applying the same op twice is applying it
         * once. Two recipes wanting the same starter is normal, and a project with it listed twice
         * is a project that fails to resolve on Maven and merely looks sloppy on Gradle.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.kitbash.core.patch.AddDependencyParityTest#buildFiles")
        @DisplayName("applying it twice adds it once")
        void isIdempotent(String path, String content) {
            Workspace workspace = projectWith(path, content);
            PatchOp.AddDependency op =
                    dependency(".", "implementation", "org.springframework.boot:spring-boot-starter-web");

            PatchApplier.applyOne(workspace, op);
            String afterOne = textOf(workspace, path);
            PatchApplier.applyOne(workspace, op);

            assertThat(textOf(workspace, path)).isEqualTo(afterOne);
            assertThat(occurrences(textOf(workspace, path), "spring-boot-starter-web"))
                    .isEqualTo(1);
        }

        /**
         * Dedupe is by coordinate rather than by the whole declaration: the same artifact asked
         * for twice with different versions is one artifact, and picking the second silently would
         * make the result depend on recipe order.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.kitbash.core.patch.AddDependencyParityTest#buildFiles")
        @DisplayName("the same artifact at two versions is still one entry")
        void dedupesByArtifact(String path, String content) {
            Workspace workspace = projectWith(path, content);

            PatchApplier.applyOne(workspace, dependency(".", "implementation", "org.flywaydb:flyway-core:11.10.5"));
            PatchApplier.applyOne(workspace, dependency(".", "implementation", "org.flywaydb:flyway-core:11.11.0"));

            assertThat(occurrences(textOf(workspace, path), "flyway-core")).isEqualTo(1);
            assertThat(textOf(workspace, path)).contains("11.10.5").doesNotContain("11.11.0");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.kitbash.core.patch.AddDependencyParityTest#buildFiles")
        @DisplayName("two different dependencies both land")
        void keepsBoth(String path, String content) {
            Workspace workspace = projectWith(path, content);

            PatchApplier.applyOne(
                    workspace, dependency(".", "implementation", "org.springframework.boot:spring-boot-starter-web"));
            PatchApplier.applyOne(workspace, dependency(".", "testImplementation", "org.assertj:assertj-core"));

            assertThat(textOf(workspace, path))
                    .contains("spring-boot-starter-web")
                    .contains("assertj-core");
        }

        /**
         * The resolution §28 needed: a recipe declares a dependency for a module and the applier
         * finds the build file, so adding a second build tool did not double the declarations.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.kitbash.core.patch.AddDependencyParityTest#buildFiles")
        @DisplayName("the op names the module, and the selection decided the format")
        void resolvesTheBuildFileFromTheModule(String path, String content) {
            Workspace workspace = projectWith(path, content);

            // The same op, unchanged, against either project.
            PatchApplier.applyOne(workspace, dependency(".", "implementation", "com.example:thing"));

            assertThat(textOf(workspace, path)).contains("com.example");
        }
    }

    @Nested
    @DisplayName("where the formats genuinely differ")
    class FormatSpecific {

        /**
         * Not a parity failure: Gradle's configurations and Maven's scopes are different
         * vocabularies for the same idea, and translating between them is what a format strategy
         * is <i>for</i>. What would be a failure is the op carrying both spellings.
         */
        @Test
        @DisplayName("a Gradle configuration becomes the Maven scope that means the same thing")
        void translatesConfigurationsToScopes() {
            Workspace maven = projectWith("pom.xml", POM);

            PatchApplier.applyOne(maven, dependency(".", "testImplementation", "org.assertj:assertj-core"));
            PatchApplier.applyOne(maven, dependency(".", "runtimeOnly", "org.postgresql:postgresql"));
            PatchApplier.applyOne(maven, dependency(".", "implementation", "com.example:library"));

            String pom = textOf(maven, "pom.xml");
            assertThat(pom).contains("<scope>test</scope>").contains("<scope>runtime</scope>");
            // `implementation` is not a Maven scope, and writing it out was the bug §28 found.
            assertThat(pom).doesNotContain("implementation").doesNotContain("runtimeOnly");
            // compile is Maven's default, and a pom somebody maintains by hand leaves it out.
            assertThat(pom).doesNotContain("<scope>compile</scope>");
        }

        @Test
        @DisplayName("a configuration with no Maven meaning is refused rather than guessed at")
        void refusesAnUnmappableConfiguration() {
            Workspace maven = projectWith("pom.xml", POM);

            assertThatThrownBy(
                            () -> PatchApplier.applyOne(maven, dependency(".", "kaptAndroidTest", "com.example:thing")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No Maven scope corresponds");
        }

        @Test
        @DisplayName("a module with no build file at all is a typed error naming the module")
        void refusesAModuleWithNoBuildFile() {
            Workspace empty = projectWith("README.md", "# nothing to build\n");

            assertThatThrownBy(
                            () -> PatchApplier.applyOne(empty, dependency(".", "implementation", "com.example:thing")))
                    .isInstanceOf(GenerationException.class);
        }

        /** A full-stack project has two build files; a dependency belongs to one of them. */
        @Test
        @DisplayName("a module target picks that module's build file, not the first one in the project")
        void picksTheRightModule() {
            Workspace fullStack = projectWith("pom.xml", POM);
            fullStack.putText("frontend/package.json", "{\n  \"dependencies\": {}\n}\n");

            PatchApplier.applyOne(fullStack, dependency("frontend", "dependencies", "zod:4.1.11"));

            assertThat(textOf(fullStack, "frontend/package.json")).contains("zod");
            assertThat(textOf(fullStack, "pom.xml")).doesNotContain("zod");
        }
    }

    private static String textOf(Workspace workspace, String path) {
        return new String(workspace.get(path).content(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int occurrences(String haystack, String needle) {
        return List.of(haystack.split(java.util.regex.Pattern.quote(needle), -1))
                        .size()
                - 1;
    }
}
