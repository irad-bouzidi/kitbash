package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No configuration value ever becomes a shell command (§13).
 *
 * <p>Every other defence in {@code kitbash-20} is about what a value may contain. This one is about
 * what the generator can do with it, and it is the stronger statement: the render, patch and
 * post-process stages cannot execute anything, so there is no shell for a value to reach however
 * it is spelled. A test that fed metacharacters through and checked the output would prove the
 * inputs it happened to try; this proves the property.
 *
 * <p>It reads source rather than bytecode on purpose. The failure it exists to catch is somebody
 * adding {@code ProcessBuilder} to a patch applier for a good local reason — running {@code
 * npm install} to refresh a lockfile, say — and a source scan names the file and line in the
 * review where that decision is still cheap to reverse.
 *
 * <p>If a stage ever genuinely needs to run something, this test is the place that argues about it:
 * it should be changed deliberately, in a commit that says why, and not quietly satisfied.
 */
class GeneratorExecutesNothingTest {

    /**
     * The modules that turn a selection into files. {@code api} is excluded because it is a web
     * application, and {@code verify} because running builds in containers is its entire job.
     */
    private static final List<String> GENERATOR_MODULES = List.of("core", "render", "catalog", "cli");

    /** Every route from the JDK to a process or a native library. */
    private static final Map<String, String> FORBIDDEN = Map.of(
            "ProcessBuilder", "starts a process",
            "Runtime.getRuntime", "reaches the process API",
            "ProcessHandle", "reaches the process API",
            "System.loadLibrary", "loads native code",
            "System.load", "loads native code",
            "Class.forName", "loads a class by name, which turns a string into code");

    @Test
    @DisplayName("no generator module can start a process, so no value can become a command")
    void nothingInTheGeneratorCanExecute() {
        List<String> violations = new ArrayList<>();

        for (String module : GENERATOR_MODULES) {
            Path sources = ReferenceProjects.repositoryRoot()
                    .resolve("server")
                    .resolve(module)
                    .resolve("src/main/java");
            for (Path file : javaFiles(sources)) {
                violations.addAll(violationsIn(
                        ReferenceProjects.repositoryRoot().relativize(file).toString(), read(file)));
            }
        }

        assertThat(violations)
                .as("§13: no configuration value may become a shell command. The generator's answer is "
                        + "that it cannot execute anything at all — if a stage now needs to, change this "
                        + "test deliberately and say why in the commit.")
                .isEmpty();
    }

    /**
     * The claim above is only worth anything if the scan would notice, so the scan is run against
     * a file that does the thing. A green test over a scanner that matches nothing looks exactly
     * like a green test over a clean codebase.
     */
    @Test
    @DisplayName("the scan catches a stage that starts a process, and its line")
    void theScanDetects() {
        List<String> offending = List.of(
                "package dev.kitbash.core.patch;",
                "",
                "class LockfileRefresher {",
                "    void refresh() throws Exception {",
                "        new " + "ProcessBuilder(\"pnpm\", \"install\").start().waitFor();",
                "    }",
                "}");

        assertThat(violationsIn("server/core/src/main/java/LockfileRefresher.java", offending))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("LockfileRefresher.java:5")
                .contains("starts a process");
    }

    /** A comment naming the hazard is the opposite of the problem, so comments do not count. */
    @Test
    @DisplayName("a comment mentioning ProcessBuilder is not a violation")
    void ignoresComments() {
        assertThat(violationsIn(
                        "Thing.java",
                        List.of(
                                "// This stage deliberately does not use " + "ProcessBuilder.",
                                " * No " + "Runtime.getRuntime() here either.")))
                .isEmpty();
    }

    /** One file's violations, as lines a reviewer can jump to. */
    private static List<String> violationsIn(String path, List<String> lines) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                continue;
            }
            int lineNumber = i + 1;
            FORBIDDEN.forEach((token, why) -> {
                if (line.contains(token)) {
                    found.add("  %s:%d uses %s, which %s".formatted(path, lineNumber, token, why));
                }
            });
        }
        return found;
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
