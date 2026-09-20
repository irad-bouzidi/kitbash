package dev.kitbash.core.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.error.Stage;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every operation gets an idempotency test and a collision test, because those two properties are
 * what let recipes be composed in any combination without the output depending on how many of them
 * happened to want the same thing.
 */
class PatchApplierTest {

    private static final RecipeId AUTH = RecipeId.of("feature-auth-jwt");
    private static final RecipeId FRONTEND = RecipeId.of("frontend-react-vite");

    private static Workspace workspaceWith(String path, String content) {
        Workspace workspace = new Workspace();
        workspace.putText(path, content);
        return workspace;
    }

    private static String text(Workspace workspace, String path) {
        return new String(workspace.get(path).content(), StandardCharsets.UTF_8);
    }

    /** Applies once, then again, and asserts the second application changed nothing. */
    private static String applyTwice(Workspace workspace, PatchOp op, String path) {
        PatchApplier.apply(workspace, List.of(op));
        String once = text(workspace, path);
        PatchApplier.apply(workspace, List.of(op));
        assertThat(text(workspace, path))
                .as("applying %s twice equals applying it once", op.operation())
                .isEqualTo(once);
        return once;
    }

    @Nested
    @DisplayName("appendLines")
    class AppendLines {

        @Test
        @DisplayName("appends what is missing and nothing that is already there")
        void appendsIdempotently() {
            Workspace workspace = workspaceWith(".gitignore", "build/\n");

            String result = applyTwice(
                    workspace,
                    new PatchOp.AppendLines(AUTH, ".gitignore", List.of("build/", ".gradle/", "node_modules/")),
                    ".gitignore");

            assertThat(result).isEqualTo("build/\n.gradle/\nnode_modules/\n");
        }

        @Test
        @DisplayName("two recipes wanting the same line produce one line")
        void deduplicatesAcrossRecipes() {
            Workspace workspace = workspaceWith(".gitignore", "build/\n");

            PatchApplier.apply(
                    workspace,
                    List.of(
                            new PatchOp.AppendLines(AUTH, ".gitignore", List.of(".env")),
                            new PatchOp.AppendLines(FRONTEND, ".gitignore", List.of(".env", "dist/"))));

            assertThat(text(workspace, ".gitignore")).isEqualTo("build/\n.env\ndist/\n");
        }
    }

    @Nested
    @DisplayName("mergeYaml")
    class MergeYaml {

        @Test
        @DisplayName("deep-merges and stays idempotent")
        void mergesDeeply() {
            Workspace workspace = workspaceWith(
                    "application.yaml",
                    """
                    spring:
                      application:
                        name: customer-management
                    """);

            String result = applyTwice(
                    workspace,
                    new PatchOp.MergeYaml(
                            AUTH,
                            "application.yaml",
                            Map.of("spring", Map.of("security", Map.of("oauth2", Map.of("issuer", "https://id"))))),
                    "application.yaml");

            assertThat(result).contains("name: customer-management").contains("issuer: https://id");
        }

        @Test
        @DisplayName("refuses a scalar collision rather than picking a winner")
        void refusesScalarCollisions() {
            // §4 calls this a feature: two recipes silently overwriting the same application.yml
            // key is precisely the bug class the typed operations exist to prevent.
            Workspace workspace = workspaceWith("application.yaml", "server:\n  port: 8080\n");

            assertThatThrownBy(() -> PatchApplier.apply(
                            workspace,
                            List.of(new PatchOp.MergeYaml(
                                    AUTH, "application.yaml", Map.of("server", Map.of("port", 9090))))))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> {
                        GenerationError error = ((GenerationException) thrown).error();
                        assertThat(error.code()).isEqualTo(ErrorCode.PATCH_COLLISION);
                        assertThat(error.message()).contains("server.port");
                        assertThat(error.recipe()).isEqualTo("feature-auth-jwt");
                    });
        }

        @Test
        @DisplayName("setting a key to the value it already has is a no-op, not a collision")
        void sameValueIsNotACollision() {
            Workspace workspace = workspaceWith("application.yaml", "server:\n  port: 8080\n");

            PatchApplier.apply(
                    workspace,
                    List.of(new PatchOp.MergeYaml(AUTH, "application.yaml", Map.of("server", Map.of("port", 8080)))));

            assertThat(text(workspace, "application.yaml")).contains("port: 8080");
        }

        @Test
        @DisplayName("lists union rather than collide, so two recipes can each add a profile")
        void listsUnion() {
            Workspace workspace = workspaceWith("application.yaml", "profiles:\n  - local\n");

            PatchApplier.apply(
                    workspace,
                    List.of(
                            new PatchOp.MergeYaml(
                                    AUTH, "application.yaml", Map.of("profiles", List.of("local", "auth"))),
                            new PatchOp.MergeYaml(FRONTEND, "application.yaml", Map.of("profiles", List.of("web")))));

            assertThat(Documents.readYaml(text(workspace, "application.yaml")).get("profiles"))
                    .isEqualTo(List.of("local", "auth", "web"));
        }

        @Test
        @DisplayName("a target that is not valid YAML is a typed error naming the file")
        void refusesMalformedTargets() {
            Workspace workspace = workspaceWith("application.yaml", "spring:\n  - a\n b: broken\n");

            assertThatThrownBy(() -> PatchApplier.apply(
                            workspace, List.of(new PatchOp.MergeYaml(AUTH, "application.yaml", Map.of("x", "y")))))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> {
                        GenerationError error = ((GenerationException) thrown).error();
                        assertThat(error.code()).isEqualTo(ErrorCode.RENDER_FAILED);
                        assertThat(error.stage()).isEqualTo(Stage.PATCH);
                        assertThat(error.file()).isEqualTo("application.yaml");
                    });
        }
    }

    @Nested
    @DisplayName("mergeJson")
    class MergeJson {

        @Test
        @DisplayName("deep-merges, unions arrays and stays idempotent")
        void mergesDeeply() {
            Workspace workspace =
                    workspaceWith("tsconfig.json", "{\n  \"compilerOptions\": {\n    \"strict\": true\n  }\n}\n");

            String result = applyTwice(
                    workspace,
                    new PatchOp.MergeJson(
                            FRONTEND,
                            "tsconfig.json",
                            Map.of("compilerOptions", Map.of("target", "ES2022"), "include", List.of("src"))),
                    "tsconfig.json");

            assertThat(result)
                    .contains("\"strict\": true")
                    .contains("\"target\": \"ES2022\"")
                    .contains("\"src\"");
        }

        @Test
        @DisplayName("refuses a scalar collision")
        void refusesCollisions() {
            Workspace workspace = workspaceWith("tsconfig.json", "{\"compilerOptions\": {\"strict\": true}}");

            assertThatThrownBy(() -> PatchApplier.apply(
                            workspace,
                            List.of(new PatchOp.MergeJson(
                                    FRONTEND, "tsconfig.json", Map.of("compilerOptions", Map.of("strict", false))))))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> assertThat(
                                    ((GenerationException) thrown).error().code())
                            .isEqualTo(ErrorCode.PATCH_COLLISION));
        }
    }

    @Nested
    @DisplayName("addScript")
    class AddScript {

        @Test
        @DisplayName("adds an npm script idempotently")
        void addsIdempotently() {
            Workspace workspace = workspaceWith("package.json", "{\n  \"name\": \"web\"\n}\n");

            String result = applyTwice(
                    workspace, new PatchOp.AddScript(FRONTEND, "package.json", "dev", "vite"), "package.json");

            assertThat(result).contains("\"scripts\": {").contains("\"dev\": \"vite\"");
        }

        @Test
        @DisplayName("fails loudly when two recipes disagree about what a script runs")
        void refusesNameCollisions() {
            Workspace workspace = workspaceWith("package.json", "{\"scripts\": {\"test\": \"vitest run\"}}");

            assertThatThrownBy(() -> PatchApplier.apply(
                            workspace, List.of(new PatchOp.AddScript(AUTH, "package.json", "test", "jest"))))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> {
                        GenerationError error = ((GenerationException) thrown).error();
                        assertThat(error.code()).isEqualTo(ErrorCode.PATCH_COLLISION);
                        assertThat(error.message()).contains("scripts.test");
                    });
        }
    }

    @Nested
    @DisplayName("insertAtMarker")
    class InsertAtMarker {

        @Test
        @DisplayName("inserts at the marker, idempotently")
        void insertsIdempotently() {
            Workspace workspace = workspaceWith(
                    "Application.java",
                    """
                    package com.acme;

                    // kitbash:imports

                    public class Application {}
                    """);

            String result = applyTwice(
                    workspace,
                    new PatchOp.InsertAtMarker(
                            AUTH, "Application.java", "// kitbash:imports", List.of("import com.acme.Security;")),
                    "Application.java");

            assertThat(result)
                    .contains("// kitbash:imports\nimport com.acme.Security;")
                    .containsOnlyOnce("import com.acme.Security;");
        }

        @Test
        @DisplayName("a missing marker names the recipe that was supposed to place it")
        void refusesMissingMarker() {
            // Appending at the end of the file instead would put an import below the class it
            // belongs to. A marker is a contract, and an unmet contract should say so.
            Workspace workspace = workspaceWith("Application.java", "package com.acme;\n");

            assertThatThrownBy(() -> PatchApplier.apply(
                            workspace,
                            List.of(new PatchOp.InsertAtMarker(
                                    AUTH, "Application.java", "// kitbash:imports", List.of("import x.Y;")))))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> {
                        GenerationError error = ((GenerationException) thrown).error();
                        assertThat(error.code()).isEqualTo(ErrorCode.PATCH_TARGET_MISSING);
                        assertThat(error.message()).contains("// kitbash:imports");
                        assertThat(error.hint()).contains("contract between recipes");
                    });
        }
    }

    @Nested
    @DisplayName("addEnvVar")
    class AddEnvVar {

        @Test
        @DisplayName("lands in .env.example and compose in one operation")
        void writesBothPlaces() {
            Workspace workspace = new Workspace();
            workspace.putText(".env.example", "# Generated by kitbash\n");
            workspace.putText("compose.yaml", "services:\n  api:\n    build: .\n");

            PatchOp op = new PatchOp.AddEnvVar(
                    AUTH, ".env.example", "compose.yaml", "api", "JWT_ISSUER", "https://id", "The token issuer");
            applyTwice(workspace, op, ".env.example");

            assertThat(text(workspace, ".env.example"))
                    .contains("# The token issuer")
                    .containsOnlyOnce("JWT_ISSUER=https://id");
            // Quoted by the YAML writer, which is correct: ${...} starts a plain scalar that a
            // strict reader would take for a flow mapping.
            assertThat(Documents.readYaml(text(workspace, "compose.yaml")))
                    .extracting("services")
                    .extracting("api")
                    .extracting("environment")
                    .isEqualTo(Map.of("JWT_ISSUER", "${JWT_ISSUER:-https://id}"));
        }

        @Test
        @DisplayName("writes only the example file when containers were not selected")
        void skipsComposeWhenAbsent() {
            Workspace workspace = workspaceWith(".env.example", "");

            PatchApplier.apply(
                    workspace,
                    List.of(new PatchOp.AddEnvVar(
                            AUTH, ".env.example", "compose.yaml", "api", "JWT_ISSUER", "https://id", null)));

            assertThat(text(workspace, ".env.example")).contains("JWT_ISSUER=https://id");
            assertThat(workspace.contains("compose.yaml")).isFalse();
        }
    }

    @Nested
    @DisplayName("addComposeService")
    class AddComposeService {

        @Test
        @DisplayName("adds a service with its depends_on, idempotently")
        void addsIdempotently() {
            Workspace workspace = workspaceWith("compose.yaml", "services:\n  api:\n    build: .\n");

            String result = applyTwice(
                    workspace,
                    new PatchOp.AddComposeService(
                            FRONTEND,
                            "compose.yaml",
                            "web",
                            Map.of("build", "./web", "ports", List.of("5173:5173")),
                            List.of("api")),
                    "compose.yaml");

            assertThat(result)
                    .contains("web:")
                    .contains("depends_on:")
                    .contains("- api")
                    .contains("api:");
        }

        @Test
        @DisplayName("refuses to redefine a service another recipe already described differently")
        void refusesCollisions() {
            Workspace workspace = workspaceWith("compose.yaml", "services:\n  db:\n    image: postgres:16\n");

            assertThatThrownBy(() -> PatchApplier.apply(
                            workspace,
                            List.of(new PatchOp.AddComposeService(
                                    AUTH, "compose.yaml", "db", Map.of("image", "mysql:8"), List.of()))))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> assertThat(
                                    ((GenerationException) thrown).error().code())
                            .isEqualTo(ErrorCode.PATCH_COLLISION));
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("a map-bearing op keeps its key order, because its contents are serialised")
        void mapOpsPreserveOrder() {
            // Map.of and Map.copyOf are unordered *and* salted: iteration order varies between JVM
            // runs by design. For a map that is only looked up in that is fine; for one whose
            // contents become bytes in a compose file it silently breaks byte-identical output.
            Map<String, Object> definition = new java.util.LinkedHashMap<>();
            definition.put("image", "postgres:16-alpine");
            definition.put("ports", List.of("5432:5432"));
            definition.put("healthcheck", Map.of("test", "pg_isready"));
            definition.put("restart", "unless-stopped");

            PatchOp.AddComposeService op =
                    new PatchOp.AddComposeService(AUTH, "compose.yaml", "db", definition, List.of());

            assertThat(op.definition().keySet()).containsExactly("image", "ports", "healthcheck", "restart");

            Workspace workspace = workspaceWith("compose.yaml", "services:\n  api:\n    build: .\n");
            PatchApplier.apply(workspace, List.of(op));
            String yaml = text(workspace, "compose.yaml");
            assertThat(yaml.indexOf("image:")).isLessThan(yaml.indexOf("restart:"));
        }

        @Test
        @DisplayName("a generation lock keeps the sort it was built with")
        void lockKeepsItsSort() {
            dev.kitbash.core.lock.Lock lock = new dev.kitbash.core.lock.Lock(
                    Map.of(
                            dev.kitbash.core.recipe.RecipeId.of("infra-docker"),
                                    dev.kitbash.core.recipe.RecipeVersion.parse("1.0.0"),
                            dev.kitbash.core.recipe.RecipeId.of("base"),
                                    dev.kitbash.core.recipe.RecipeVersion.parse("1.0.0"),
                            dev.kitbash.core.recipe.RecipeId.of("ci-gitlab"),
                                    dev.kitbash.core.recipe.RecipeVersion.parse("1.0.0")),
                    "sha256:abc");

            assertThat(lock.recipeVersions().keySet())
                    .extracting(dev.kitbash.core.recipe.RecipeId::value)
                    .containsExactly("base", "ci-gitlab", "infra-docker");
        }
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("a patch against an unproduced target yields the exact §14 envelope")
        void refusesUnproducedTargets() {
            Workspace workspace = new Workspace();

            assertThatThrownBy(() -> PatchApplier.apply(
                            workspace,
                            List.of(new PatchOp.MergeYaml(
                                    AUTH, "backend/src/main/resources/application.yml", Map.of("a", "b")))))
                    .isInstanceOf(GenerationException.class)
                    .satisfies(thrown -> {
                        GenerationError error = ((GenerationException) thrown).error();
                        assertThat(error.code()).isEqualTo(ErrorCode.PATCH_TARGET_MISSING);
                        assertThat(error.stage()).isEqualTo(Stage.PATCH);
                        assertThat(error.recipe()).isEqualTo("feature-auth-jwt");
                        assertThat(error.file()).isEqualTo("backend/src/main/resources/application.yml");
                        assertThat(error.message()).isEqualTo("Patch target was not produced by any selected recipe.");
                        assertThat(error.hint()).contains("feature-auth-jwt").contains("has to be selected");
                    });
        }
    }
}
