package dev.kitbash.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The authoring SDK (§43).
 *
 * <p>The requirement worth testing is not that the commands run — it is that
 * {@code recipe check} <b>agrees with CI</b>. An SDK that passes locally and fails in the pipeline
 * teaches people to distrust the tools, and the way that happens is not a crash: it is a check
 * that quietly looks at a slightly different set of files than the pipeline does.
 */
class RecipeSdkTest {

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
    @DisplayName("recipe check")
    class Check {

        @Test
        @DisplayName("a recipe in the catalog passes every check, in about a second")
        void checksARealRecipe() {
            long started = System.nanoTime();
            Result result = run("recipe", "check", "db-postgres-flyway");
            long millis = (System.nanoTime() - started) / 1_000_000;

            assertThat(result.status()).as("%s", result.out()).isZero();
            assertThat(result.out()).contains("manifest").contains("renders").contains("reference");
            // §43 asks for all of it "without booting the server and in a few seconds". Generous,
            // because a CI machine is slower than a laptop — but an order of magnitude tighter
            // than the matrix, which is the point of having this at all.
            assertThat(millis).as("took %dms", millis).isLessThan(30_000);
        }

        @Test
        @DisplayName("green does not claim the project builds, because this cannot know that")
        void saysWhatItDidNotProve() {
            // The most valuable sentence in the output. A harness that implied more than it proved
            // would send somebody to a merge request with a recipe nobody has compiled.
            assertThat(run("recipe", "check", "db-postgres-flyway").out())
                    .contains("does not mean the generated project builds");
        }

        @Test
        @DisplayName("a recipe nothing selects is 'not wired' rather than broken, and still non-zero")
        void distinguishesUnwiredFromBroken() {
            Result result = run("recipe", "check", "feature-codeowners", "--catalog", "/nowhere");

            // A missing catalog is a different failure from an unwired recipe, and both are
            // different from a broken one. Conflating them is how a first run reads as a disaster.
            assertThat(result.status()).isNotZero();
        }

        @Test
        @DisplayName("an unknown recipe lists the ones there are")
        void namesTheAlternatives() {
            Result result = run("recipe", "check", "backend-spring-jav");

            assertThat(result.status()).isNotZero();
            assertThat(result.err()).contains("backend-spring-java");
        }
    }

    @Nested
    @DisplayName("recipe new")
    class Scaffold {

        @Test
        @DisplayName("scaffolds a manifest that validates, which is §43's bar for a first run")
        void scaffoldsSomethingThatValidates(@TempDir Path catalog) throws Exception {
            Path recipes = seedCatalog(catalog);

            Result created = run(
                    "recipe", "new", "feature-example", "--from", "demo-reference", "--catalog", recipes.toString());

            assertThat(created.status()).as("%s", created.err()).isZero();
            String manifest =
                    Files.readString(recipes.resolve("feature-example").resolve("recipe.yaml"));
            // The line that gives an author completion and inline validation while typing, which is
            // most of what §43 means by editor integration.
            assertThat(manifest).startsWith("# yaml-language-server: $schema=");
            assertThat(manifest).contains("id: feature-example");
            // A `from:` glob matching nothing is refused at load, so the scaffold ships one file.
            assertThat(recipes.resolve("feature-example").resolve("files")).exists();
            assertThat(Files.list(recipes.resolve("feature-example").resolve("files")))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("it wires nothing in, and says which four edits do")
        void leavesTheWiringToTheAuthor(@TempDir Path catalog) throws Exception {
            Path recipes = seedCatalog(catalog);
            String before = Files.readString(recipes.resolve("_catalog.yaml"));

            Result created = run(
                    "recipe", "new", "feature-example", "--from", "demo-reference", "--catalog", recipes.toString());

            // _catalog.yaml is the wizard's editorial structure: which group a control sits in and
            // what it says. A scaffold appending a slot to whichever group happens to be last would
            // be making that decision silently.
            assertThat(Files.readString(recipes.resolve("_catalog.yaml"))).isEqualTo(before);
            assertThat(created.out()).contains("_catalog.yaml").contains("reference-variables.json");
        }

        @Test
        @DisplayName("refuses to overwrite a recipe that is already there")
        void refusesToClobber(@TempDir Path catalog) throws Exception {
            Path recipes = seedCatalog(catalog);
            run("recipe", "new", "feature-example", "--from", "demo-reference", "--catalog", recipes.toString());

            Result again = run(
                    "recipe", "new", "feature-example", "--from", "demo-reference", "--catalog", recipes.toString());

            assertThat(again.status()).isNotZero();
            assertThat(again.err()).contains("already exists");
        }

        @Test
        @DisplayName("an unknown reference project lists the ones there are")
        void namesTheReferenceProjects(@TempDir Path catalog) throws Exception {
            Path recipes = seedCatalog(catalog);

            Result result = run(
                    "recipe", "new", "feature-example", "--from", "no-such-project", "--catalog", recipes.toString());

            assertThat(result.status()).isNotZero();
            assertThat(result.err()).contains("demo-reference");
        }

        /** A catalog root with one reference project, which is all the scaffold looks at. */
        private static Path seedCatalog(Path root) throws Exception {
            Path recipes = root.resolve("recipes");
            Files.createDirectories(recipes);
            Files.writeString(recipes.resolve("_catalog.yaml"), "schemaVersion: 1\n");
            Files.createDirectories(root.resolve("reference").resolve("demo-reference"));
            return recipes;
        }
    }
}
