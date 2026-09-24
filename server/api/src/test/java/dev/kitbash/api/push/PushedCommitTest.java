package dev.kitbash.api.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.render.PebbleRenderStage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * §46's exit criterion:
 *
 * <blockquote>
 * One click produces a GitLab project whose initial commit is byte-identical to the zip's initial
 * commit, verified by a test that generates both and compares the commit trees.
 * </blockquote>
 *
 * <p>Against a bare repository on disk rather than a GitLab instance. What GitLab adds to a push is
 * authentication and a policy check; what this asserts is that the object which arrives is the
 * object which left, and git's transfer protocol does not care who is listening. A stubbed GitLab
 * would test a stub.
 *
 * <p>The assertion is stronger than it first looks, and the reason is §4. The commit's author,
 * committer, timestamp, timezone and message are all fixed, so its hash is a function of the
 * content — which means "byte-identical" is checkable as a single string comparison, and a push
 * path that re-rendered would produce a different one.
 */
class PushedCommitTest {

    private static final SelectionEnvelope SELECTION = SelectionEnvelope.current(
            "pushed",
            Map.of(
                    "backend", "backend-spring-java",
                    "buildTool", "build-gradle-kts",
                    "database", "db-postgres-flyway"),
            Map.of(
                    "groupId", "com.example",
                    "packageName", "com.example.pushed",
                    "javaVersion", "21",
                    "entityName", "Widget",
                    "entityTable", "widgets",
                    "envPrefix", "PUSHED"));

    private static GeneratedProject generate() {
        CatalogLoader.LoadedCatalog loaded = new CatalogLoader().loadAll(recipes());
        return GenerationPipeline.over(loaded.catalog(), loaded.content(), new PebbleRenderStage())
                .generate(SELECTION);
    }

    @Nested
    @DisplayName("the pushed commit")
    class Pushed {

        @Test
        @DisplayName("is the one the zip carries, not one made for the push")
        void isTheSameCommit(@TempDir Path directory) throws Exception {
            assumeTrue(gitIsAvailable(), "git is not on PATH");

            Path remote = directory.resolve("remote.git");
            run(directory, List.of("git", "init", "-q", "--bare", remote.toString()));

            GeneratedProject project = generate();
            GitPusher.Pushed pushed = new GitPusher().push(project, remote.toString());

            // The commit the generator recorded, the commit the pusher reported, and the commit
            // now on the remote. Three names for one object, or the guarantee is not real.
            String onTheRemote = capture(remote, List.of("git", "rev-parse", "main"));
            assertThat(pushed.commitId()).isEqualTo(project.commitId());
            assertThat(onTheRemote).isEqualTo(project.commitId());
        }

        @Test
        @DisplayName("carries the same tree, file for file, as the generated workspace")
        void carriesTheSameTree(@TempDir Path directory) throws Exception {
            assumeTrue(gitIsAvailable(), "git is not on PATH");

            Path remote = directory.resolve("remote.git");
            run(directory, List.of("git", "init", "-q", "--bare", remote.toString()));
            GeneratedProject project = generate();
            new GitPusher().push(project, remote.toString());

            List<String> onTheRemote = capture(remote, List.of("git", "ls-tree", "-r", "--name-only", "main"))
                    .lines()
                    .sorted()
                    .toList();
            List<String> generated = project.workspace().files().keySet().stream()
                    // The repository's own .git is not in the commit; it is what holds it.
                    .filter(path -> !path.startsWith(".git/"))
                    .sorted()
                    .toList();

            assertThat(onTheRemote).isEqualTo(generated);
        }

        @Test
        @DisplayName("a second generation pushes the identical commit, which is what makes it checkable")
        void isDeterministic() {
            // §4's byte-equality, expressed as one string. If this ever stops holding, the claim
            // in §46 stops being checkable at all rather than merely being violated.
            assertThat(generate().commitId()).isEqualTo(generate().commitId());
        }
    }

    @Nested
    @DisplayName("when it goes wrong")
    class Failures {

        @Test
        @DisplayName("a remote that does not exist is a push failure, with git's own words")
        void reportsGitsReason(@TempDir Path directory) {
            assumeTrue(gitIsAvailable(), "git is not on PATH");

            assertThatThrownBy(() -> new GitPusher()
                            .push(
                                    generate(),
                                    directory.resolve("nothing-here.git").toString()))
                    .isInstanceOf(PushFailedException.class);
        }

        @Test
        @DisplayName("a token in a remote never reaches a message or a log")
        void redactsCredentials() {
            String output = "fatal: unable to access 'https://oauth2:glpat-SECRETVALUE@gitlab.com/g/p.git/'";

            // The remote carries a credential and a §14 envelope is shown to a user and written to
            // a log — which is exactly where one must not be.
            assertThat(GitPusher.redact(output))
                    .doesNotContain("glpat-SECRETVALUE")
                    .contains("https://***@");
        }

        @Test
        @DisplayName("the line shown is the one that says why, not the banner around it")
        void picksTheUsefulLine() {
            String output =
                    """
                    Enumerating objects: 42, done.
                    remote: GitLab: You are not allowed to push code to this project.
                    To https://gitlab.com/group/project.git
                    """;

            // Showing all of git's output buries the answer; showing none of it turns a group
            // policy rejection into "push failed".
            assertThat(GitPusher.firstUsefulLine(output))
                    .isEqualTo("GitLab: You are not allowed to push code to this project.");
        }
    }

    private static boolean gitIsAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException absent) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void run(Path directory, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static String capture(Path directory, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return output.strip();
    }

    private static Path recipes() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("recipes"))) {
            candidate = candidate.getParent();
        }
        return candidate.resolve("recipes");
    }
}
