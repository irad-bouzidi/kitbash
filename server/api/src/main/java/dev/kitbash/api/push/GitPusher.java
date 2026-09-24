package dev.kitbash.api.push;

import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.workspace.GeneratedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pushes the project that was generated — not one generated again (§4, §46).
 *
 * <h2>Why the commit is identical without trying</h2>
 *
 * <p>The zip already contains a real repository: {@code GitSkeletonWriter} writes loose objects, a
 * tree, a commit and a ref into the workspace, with the author, committer, timestamp, timezone and
 * message all fixed so that §4's byte-equality survives. So the commit hash is a function of the
 * content, and two generations of one selection produce the same one.
 *
 * <p>This writes <b>that workspace</b> to a temporary directory and pushes it. Nothing re-renders
 * and nothing re-commits, which is what §46 means by <i>push server-side from the generation
 * pipeline's output</i> — and the reason it says so is that two code paths producing "the same"
 * commit is how they stop being the same.
 *
 * <h2>The git binary</h2>
 *
 * <p>Shelling out rather than reimplementing the smart-HTTP protocol or adding JGit. The cost is
 * that the runtime image must carry {@code git} — it does, deliberately, and a deployment that
 * builds its own image and omits it gets the named error below rather than a missing class at the
 * moment somebody presses the button.
 */
@Component
public class GitPusher {

    private static final Logger log = LoggerFactory.getLogger(GitPusher.class);

    /** Long enough for a large repository over a slow link; short enough not to pin a thread. */
    private static final int TIMEOUT_SECONDS = 120;

    /** What a push produced, or why it did not. */
    public record Pushed(String commitId, String branch) {}

    /**
     * Writes the generated project to disk and pushes it to {@code remote}.
     *
     * @param remote an https URL carrying credentials, which is why it is never logged
     * @return the commit that is now on the remote — the same one the zip carries
     */
    public Pushed push(GeneratedProject project, String remote) {
        Path directory = temporaryDirectory();
        try {
            materialise(project, directory);
            Path root = directory.resolve(project.projectName());

            run(root, List.of("git", "push", remote, "HEAD:refs/heads/main"));
            log.info(
                    "Pushed selection={} commit={} files={}",
                    project.selectionHash(),
                    project.commitId(),
                    project.fileCount());
            return new Pushed(project.commitId(), "main");
        } finally {
            delete(directory);
        }
    }

    /**
     * The workspace on disk, modes and all.
     *
     * <p>The executable bit matters for the same reason it does in the zip: a {@code gradlew}
     * written 0644 is a project whose first documented command fails, and here it would be
     * committed that way to somebody's group.
     */
    private static void materialise(GeneratedProject project, Path directory) {
        Path root = directory.resolve(project.projectName());
        for (Map.Entry<String, GeneratedFile> entry :
                project.workspace().files().entrySet()) {
            Path file = root.resolve(entry.getKey());
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue().content());
                if (entry.getValue().executable()) {
                    file.toFile().setExecutable(true, false);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write " + entry.getKey(), e);
            }
        }
    }

    /**
     * Runs one git command, turning a failure into something a user can act on.
     *
     * <p>The remote is never in the message. It carries a token, and a §14 envelope is shown to a
     * user and written to a log — which is exactly where a credential must not be.
     */
    private static void run(Path directory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new PushFailedException("the push did not finish within " + TIMEOUT_SECONDS + " seconds", null);
            }
            if (process.exitValue() != 0) {
                throw new PushFailedException(firstUsefulLine(output), redact(output));
            }
        } catch (IOException notInstalled) {
            // The deployment's problem, not the caller's, and it is worth saying which.
            throw new PushFailedException(
                    "git is not available on the server, so nothing can be pushed", notInstalled.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PushFailedException("the push was interrupted", null);
        }
    }

    /**
     * The line of git's output worth showing.
     *
     * <p>git prints progress, hints and a banner; the line that says what went wrong is usually the
     * one beginning {@code remote:} or {@code error:}. Showing all of it would bury the answer, and
     * showing none of it would mean a group policy rejection reads as "push failed".
     */
    static String firstUsefulLine(String output) {
        return output.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("remote:") || line.startsWith("error:") || line.startsWith("fatal:"))
                .map(line -> line.replaceFirst("^(remote|error|fatal):\\s*", ""))
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("the push was rejected");
    }

    /** Anything that looks like credentials in a URL, removed before it reaches a log. */
    static String redact(String output) {
        return output.replaceAll("https://[^@\\s]+@", "https://***@");
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("kitbash-push-");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create a directory to push from", e);
        }
    }

    private static void delete(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temporary directory that outlives one request is untidy, not broken.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
