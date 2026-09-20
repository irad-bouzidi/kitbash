package dev.kitbash.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI, driven as a function rather than as a process.
 *
 * <p>{@link Kitbash#run} takes its streams, so these tests read real output without forking a JVM
 * — which keeps them fast enough to run on every save. That the real entry point is a two-line
 * {@code main} around it is the point: there is nowhere for behaviour to hide.
 */
class KitbashCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Result(int code, String out, String err) {}

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Kitbash.run(
                List.of(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("recipes"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate).as("repository root").isNotNull();
        return candidate;
    }

    private static Path catalog() {
        return repositoryRoot().resolve("recipes");
    }

    private static Path selection(Path directory, String json) {
        try {
            Path file = directory.resolve("selection.json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String FULL_STACK =
            """
            {
              "schemaVersion": 1,
              "projectName": "cli-cell",
              "options": {
                "buildTool": "build-gradle-kts",
                "backend": "backend-spring-java",
                "database": "db-postgres-flyway",
                "frontend": "frontend-react-vite",
                "docker": true
              },
              "variables": {
                "groupId": "com.acme",
                "packageName": "com.acme.cli",
                "javaVersion": "21",
                "entityName": "Widget",
                "entityTable": "widgets",
                "envPrefix": "CLI"
              }
            }""";

    @Nested
    @DisplayName("generate")
    class Generate {

        @Test
        @DisplayName("writes a project tree, with gradlew still executable")
        void writesATree(@TempDir Path directory) {
            Path out = directory.resolve("project");

            Result result = run(
                    "generate",
                    "--selection",
                    selection(directory, FULL_STACK).toString(),
                    "--out",
                    out.toString(),
                    "--catalog",
                    catalog().toString());

            assertThat(result.code()).describedAs(result.err()).isZero();
            assertThat(out.resolve("build.gradle.kts")).exists();
            assertThat(out.resolve("frontend/package.json")).exists();
            assertThat(out.resolve(".git/HEAD")).exists();
            // A gradlew written 0644 makes the first command the README gives you fail.
            assertThat(Files.isExecutable(out.resolve("gradlew"))).isTrue();
            assertThat(result.out()).contains("selection ").contains("catalog sha256:");
        }

        @Test
        @DisplayName("writes one deterministic zip with --zip, byte-identical across runs")
        void writesAZip(@TempDir Path directory) throws IOException {
            Path selection = selection(directory, FULL_STACK);
            Path first = directory.resolve("first.zip");
            Path second = directory.resolve("nested/second.zip");

            assertThat(run(
                                    "generate",
                                    "--selection",
                                    selection.toString(),
                                    "--out",
                                    first.toString(),
                                    "--zip",
                                    "--catalog",
                                    catalog().toString())
                            .code())
                    .isZero();
            assertThat(run(
                                    "generate",
                                    "--selection",
                                    selection.toString(),
                                    "--out",
                                    second.toString(),
                                    "--zip",
                                    "--catalog",
                                    catalog().toString())
                            .code())
                    .isZero();

            assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
            assertThat(Files.readAllBytes(first)).startsWith('P', 'K');
        }

        @Test
        @DisplayName("a refused selection exits non-zero with the §14 envelope as JSON on stderr")
        void printsTheErrorEnvelope(@TempDir Path directory) throws IOException {
            Path selection = selection(
                    directory,
                    """
                    {"schemaVersion":1,"projectName":"svc",
                     "options":{"backend":"backend-spring-jva"},"variables":{}}""");

            Result result = run(
                    "generate",
                    "--selection",
                    selection.toString(),
                    "--out",
                    directory.resolve("out").toString(),
                    "--catalog",
                    catalog().toString());

            assertThat(result.code()).isEqualTo(1);
            // CI is the caller that matters: it has to read this, not grep it.
            JsonNode envelope = JSON.readTree(result.err());
            assertThat(envelope.path("error").asText()).isEqualTo("UNKNOWN_RECIPE");
            assertThat(envelope.path("stage").asText()).isEqualTo("parse");
            assertThat(envelope.path("hint").asText()).contains("backend-spring-java");
        }
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("resolves without generating, and says what the stack would be")
        void resolvesOnly(@TempDir Path directory) throws IOException {
            Result result = run(
                    "validate",
                    "--selection",
                    selection(directory, FULL_STACK).toString(),
                    "--catalog",
                    catalog().toString());

            assertThat(result.code()).isZero();
            JsonNode document = JSON.readTree(result.out());
            assertThat(document.path("valid").asBoolean()).isTrue();
            assertThat(document.path("recipes")).isNotEmpty();
            assertThat(directory.resolve("project")).doesNotExist();
        }

        @Test
        @DisplayName("exits non-zero when the selection cannot be generated from")
        void failsOnConflict(@TempDir Path directory) throws IOException {
            Path selection = selection(
                    directory,
                    """
                    {"schemaVersion":1,"projectName":"svc",
                     "options":{"backend":"backend-spring-java"},"variables":{}}""");

            Result result = run(
                    "validate",
                    "--selection",
                    selection.toString(),
                    "--catalog",
                    catalog().toString());

            assertThat(result.code()).isEqualTo(1);
            assertThat(JSON.readTree(result.err()).path("error").asText()).isNotEmpty();
            // Still prints the resolution: "will this work?" is better answered with what it
            // would have produced.
            assertThat(JSON.readTree(result.out()).path("valid").asBoolean()).isFalse();
        }
    }

    @Nested
    @DisplayName("catalog")
    class CatalogDump {

        @Test
        @DisplayName("dumps a metadata document with the same digest the loader computed")
        void dumpsTheCatalog() throws IOException {
            Result result = run("catalog", "--json", "--catalog", catalog().toString());

            assertThat(result.code()).isZero();
            JsonNode document = JSON.readTree(result.out());
            assertThat(document.path("catalogDigest").asText()).startsWith("sha256:");
            assertThat(document.path("groups")).isNotEmpty();
            assertThat(document.path("recipeCount").asInt()).isPositive();
        }
    }

    @Nested
    @DisplayName("the command line itself")
    class CommandLine {

        @Test
        @DisplayName("a missing required flag is exit 2 and usage, not a stack trace")
        void refusesABadCommandLine() {
            assertThat(run("generate").code()).isEqualTo(2);
            assertThat(run("generate").err()).contains("--selection is required");
            assertThat(run("nonsense").code()).isEqualTo(2);
            assertThat(run().code()).isEqualTo(2);
            assertThat(run("--help").code()).isZero();
        }

        @Test
        @DisplayName("a selection file that is not there names the path it looked at")
        void namesAMissingFile() {
            Result result = run("validate", "--selection", "/nowhere/selection.json");

            assertThat(result.code()).isEqualTo(2);
            assertThat(result.err()).contains("/nowhere/selection.json");
        }
    }
}
