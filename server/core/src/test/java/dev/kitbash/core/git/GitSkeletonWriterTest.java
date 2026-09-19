package dev.kitbash.core.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the skeleton with git itself rather than by re-implementing git's reader in the assertions.
 * A hand-written object format that only this project can read would pass a self-consistent test
 * and fail the first time a user ran {@code git log}.
 */
class GitSkeletonWriterTest {

    private static boolean gitAvailable;

    @BeforeAll
    static void detectGit() {
        try {
            gitAvailable = new ProcessBuilder("git", "--version").start().waitFor(20, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            gitAvailable = false;
        }
    }

    @Test
    @DisplayName("the same workspace always produces the same commit id")
    void commitIdIsStable() {
        assertThat(GitSkeletonWriter.write(sampleWorkspace(), "Initial commit"))
                .isEqualTo(GitSkeletonWriter.write(sampleWorkspace(), "Initial commit"));
    }

    @Test
    @DisplayName("a different message is a different commit, so the id is not a constant")
    void commitIdDependsOnContent() {
        assertThat(GitSkeletonWriter.write(sampleWorkspace(), "Initial commit"))
                .isNotEqualTo(GitSkeletonWriter.write(sampleWorkspace(), "Something else"));
    }

    @Test
    @DisplayName("HEAD points at main, and main at the commit")
    void refsPointAtTheCommit() {
        Workspace workspace = sampleWorkspace();
        String commitId = GitSkeletonWriter.write(workspace, "Initial commit");

        assertThat(text(workspace, ".git/HEAD")).isEqualTo("ref: refs/heads/main\n");
        assertThat(text(workspace, ".git/refs/heads/main")).isEqualTo(commitId + "\n");
        assertThat(workspace.contains(".git/index")).isTrue();
    }

    @Test
    @DisplayName("committing nothing is refused rather than producing an empty repository")
    void refusesEmptyWorkspace() {
        assertThatThrownBy(() -> GitSkeletonWriter.write(new Workspace(), "Initial commit"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("git log shows exactly one commit, by the fixed author, at the fixed timestamp")
    void gitLogShowsOneCommit(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable, "git is not on the PATH");
        Workspace workspace = sampleWorkspace();
        String commitId = GitSkeletonWriter.write(workspace, "Initial commit");
        materialise(workspace, directory);

        assertThat(git(directory, "log", "--format=%H|%an|%ae|%at|%s").lines().toList())
                .containsExactly(commitId + "|kitbash|noreply@kitbash.dev|1577836800|Initial commit");
    }

    @Test
    @DisplayName("git status is clean: the index matches the tree that was written")
    void gitStatusIsClean(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable, "git is not on the PATH");
        Workspace workspace = sampleWorkspace();
        GitSkeletonWriter.write(workspace, "Initial commit");
        materialise(workspace, directory);

        assertThat(git(directory, "status", "--porcelain")).isEmpty();
    }

    @Test
    @DisplayName("git fsck finds no corruption")
    void gitFsckIsQuiet(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable, "git is not on the PATH");
        Workspace workspace = sampleWorkspace();
        GitSkeletonWriter.write(workspace, "Initial commit");
        materialise(workspace, directory);

        assertThat(git(directory, "fsck", "--strict")).isEmpty();
    }

    @Test
    @DisplayName("the executable bit survives into the committed tree")
    void executableBitIsCommitted(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable, "git is not on the PATH");
        Workspace workspace = sampleWorkspace();
        GitSkeletonWriter.write(workspace, "Initial commit");
        materialise(workspace, directory);

        Map<String, String> modes = git(directory, "ls-tree", "-r", "HEAD")
                .lines()
                .map(line -> line.split("\\s+", 4))
                .collect(java.util.stream.Collectors.toMap(parts -> parts[3], parts -> parts[0]));

        assertThat(modes).containsEntry("gradlew", "100755").containsEntry("src/Main.java", "100644");
    }

    private static Workspace sampleWorkspace() {
        Workspace workspace = new Workspace();
        workspace.putText("README.md", "# demo\n");
        workspace.putText("src/Main.java", "class Main {}\n");
        workspace.putText("src/deeply/nested/File.java", "class File {}\n");
        workspace.put("gradlew", GeneratedFile.executable("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8)));
        return workspace;
    }

    private static String text(Workspace workspace, String path) {
        return new String(workspace.get(path).content(), StandardCharsets.UTF_8);
    }

    /** Writes the workspace to disk exactly as unzipping it would, modes included. */
    private static void materialise(Workspace workspace, Path root) throws IOException {
        for (Map.Entry<String, GeneratedFile> file : workspace.files().entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue().content());
            if (file.getValue().executable()) {
                assertThat(target.toFile().setExecutable(true, false)).isTrue();
            }
        }
    }

    private static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);

        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .describedAs("git %s failed:%n%s", String.join(" ", arguments), output)
                .isZero();
        return output.strip();
    }
}
