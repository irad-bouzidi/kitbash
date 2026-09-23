package dev.kitbash.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.core.error.GenerationError;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a released binary says about itself, and how it fails (§8, §14, §42).
 *
 * <p>§42: <i>a CLI carries its catalog, so which catalog it carries is part of its identity.</i>
 * Everything here follows from that. Two installs of "kitbash 0.1.0" built from different commits
 * generate different projects, and the digest is the only thing that distinguishes them — so it is
 * in {@code --version}, and a stale binary emitting a stale catalog is visible rather than
 * surprising.
 */
class DistributionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Kitbash.run(
                List.of(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int status, String out, String err) {}

    @Nested
    @DisplayName("--version")
    class Version {

        @Test
        @DisplayName("reports the tool version and the catalog digest it would generate from")
        void reportsBoth() {
            Result result = run("--version");

            assertThat(result.status()).isZero();
            assertThat(result.out()).startsWith("kitbash ");
            // §8's reason for exposing the digest applies here too: it is what makes a bug report
            // actionable. "kitbash 0.1.0 produced this" identifies nothing on its own.
            assertThat(result.out()).contains("catalog sha256:");
            // And where it came from, because "which catalog" is the question a wrong digest
            // prompts, and the answer is a path.
            assertThat(result.out()).contains("  from ");
        }

        @Test
        @DisplayName("the digest is of the catalog named by --catalog, not of one recorded at build time")
        void describesTheCatalogItWouldUse() {
            // A recorded digest would describe what was intended at build time. These differ
            // exactly when somebody needs to know — which is when they have pointed the binary at
            // a different recipe tree.
            String repository = run("--version").out();
            String explicit = run("--version", "--catalog", catalogPath()).out();

            assertThat(explicit).contains("catalog sha256:");
            assertThat(explicit).isEqualTo(repository);
        }

        @Test
        @DisplayName("a version with no catalog still says which binary this is")
        void answersTheHalfItCan() {
            Result result = run("--version", "--catalog", "/nowhere/at/all");

            // "Which binary is this" is answerable even when "which catalog does it carry" is not,
            // and refusing both would withhold the half that works.
            assertThat(result.out()).startsWith("kitbash ");
            assertThat(result.out()).contains("catalog unavailable");
            assertThat(result.status()).isEqualTo(1);
        }

        private static String catalogPath() {
            java.nio.file.Path candidate = java.nio.file.Path.of("").toAbsolutePath();
            while (candidate != null && !java.nio.file.Files.isDirectory(candidate.resolve("recipes"))) {
                candidate = candidate.getParent();
            }
            return candidate.resolve("recipes").toString();
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        private static final GenerationError ERROR =
                GenerationError.unknownRecipe("backend-spring-jav", Set.of("backend-spring-java"));

        @Test
        @DisplayName("a pipe gets the envelope and nothing else, so a script can parse stderr whole")
        void machineReadable() throws Exception {
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            ErrorOutput.print(ERROR, new PrintStream(err, true, StandardCharsets.UTF_8), false);

            // §14 has CI parsing stderr as the envelope. A line of prose above it is a line the
            // parser has to skip, and a parser that skips lines is one that will skip the wrong one.
            String written = err.toString(StandardCharsets.UTF_8);
            assertThat(JSON.readTree(written).path("error").asText()).isEqualTo("UNKNOWN_RECIPE");
            assertThat(written.stripLeading()).startsWith("{");
        }

        @Test
        @DisplayName("a terminal gets the message and the next action first, then the envelope")
        void humanReadable() throws Exception {
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            ErrorOutput.print(ERROR, new PrintStream(err, true, StandardCharsets.UTF_8), true);

            String written = err.toString(StandardCharsets.UTF_8);
            assertThat(written).startsWith("UNKNOWN_RECIPE: ");
            assertThat(written).contains(ERROR.hint());
            // Still parseable: the envelope is present and last, so redirecting a terminal run to
            // a file loses nothing a script needed.
            assertThat(JSON.readTree(written.substring(written.indexOf('{')))
                            .path("hint")
                            .asText())
                    .isEqualTo(ERROR.hint());
        }
    }
}
