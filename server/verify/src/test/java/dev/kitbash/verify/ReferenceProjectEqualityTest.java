package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.catalog.LoadedRecipe;
import dev.kitbash.core.pipeline.GeneratedProject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binds every recipe to the reference project it was extracted from (§4).
 *
 * <p>Without this the reference projects quietly become documentation that lies: somebody fixes a
 * bug in the reference, nobody ports it into the recipe, and the emitted project keeps the bug. It
 * is also what makes recipe maintenance bearable — edit the real project, run this, and the diff
 * says exactly what to port.
 *
 * <p>Nothing is compiled here. This test answers a different question from the verification matrix:
 * not "does the output build?" but "is the output still the project we maintain?"
 */
class ReferenceProjectEqualityTest {

    static Stream<Path> referenceProjects() {
        return ReferenceProjects.referenceProjects().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceProjects")
    @DisplayName("generating with the checked-in variables reproduces the reference project")
    void reproducesTheReferenceProject(Path referenceProject) {
        List<LoadedRecipe> loaded = ReferenceProjects.loadRecipes();
        GeneratedProject generated =
                ReferenceProjects.pipeline(loaded).generate(ReferenceProjects.selectionFor(referenceProject));

        if (Boolean.getBoolean("kitbash.reference.adopt")) {
            // Extraction-time only, and the opposite of the normal workflow. When recipes are first
            // derived from a reference project, composition legitimately reorders things a human
            // wrote by hand — .gitignore sections land in apply order, a patched YAML comes back
            // through its serialiser. Adopting the generated tree once, and reviewing that diff in
            // the commit, is how the two are brought into agreement. Afterwards the direction
            // reverses for good: edit the reference, run this test, port the diff into the recipe.
            adopt(referenceProject, generated, ReferenceProjects.excluded(referenceProject));
            return;
        }

        Map<String, byte[]> expected = readTree(referenceProject, ReferenceProjects.excluded(referenceProject));
        Map<String, byte[]> actual = new TreeMap<>();
        generated.workspace().files().forEach((path, file) -> {
            // The git skeleton is generated, never checked in: a reference project is a working
            // tree, and its own .git belongs to this repository.
            if (!path.startsWith(".git/")) {
                actual.put(path, file.content());
            }
        });

        assertThat(diff(expected, actual)).as("%s", describe(referenceProject)).isEmpty();
    }

    private static void adopt(Path referenceProject, GeneratedProject generated, List<String> excluded) {
        generated.workspace().files().forEach((path, file) -> {
            if (path.startsWith(".git/") || excluded.stream().anyMatch(rule -> matches(path, rule))) {
                return;
            }
            try {
                Path target = referenceProject.resolve(path);
                Files.createDirectories(target.getParent());
                Files.write(target, file.content());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not adopt " + path, e);
            }
        });
    }

    /**
     * A real diff. "Trees are not equal" is not an acceptable failure message: what a maintainer
     * needs is which files are missing, which are extra, and for a differing file the first line
     * that differs with a little context either side.
     */
    private static String diff(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        List<String> report = new ArrayList<>();

        expected.keySet().stream()
                .filter(path -> !actual.containsKey(path))
                .forEach(path -> report.add("  missing from the generated project: " + path));
        actual.keySet().stream()
                .filter(path -> !expected.containsKey(path))
                .forEach(path -> report.add("  generated but not in the reference: " + path));

        expected.forEach((path, expectedBytes) -> {
            byte[] actualBytes = actual.get(path);
            if (actualBytes == null || java.util.Arrays.equals(expectedBytes, actualBytes)) {
                return;
            }
            report.add("  differs: " + path + System.lineSeparator() + firstDifference(expectedBytes, actualBytes));
        });

        return report.isEmpty() ? "" : String.join(System.lineSeparator(), report);
    }

    private static String firstDifference(byte[] expected, byte[] actual) {
        List<String> expectedLines = lines(expected);
        List<String> actualLines = lines(actual);
        int limit = Math.min(expectedLines.size(), actualLines.size());
        for (int i = 0; i < limit; i++) {
            if (!expectedLines.get(i).equals(actualLines.get(i))) {
                return context(expectedLines, actualLines, i);
            }
        }
        if (expectedLines.size() != actualLines.size()) {
            List<String> longer = expectedLines.size() > actualLines.size() ? expectedLines : actualLines;
            String side = expectedLines.size() > actualLines.size() ? "reference" : "generated";
            return "    the files agree for %d lines, then %s continues:%n      %s%n"
                    .formatted(limit, side, longer.get(limit));
        }
        // Same lines, different bytes: a trailing newline, or whitespace a line-based view hides.
        return "    every line matches; the difference is trailing bytes (%d vs %d)%n"
                .formatted(expected.length, actual.length);
    }

    private static String context(List<String> expected, List<String> actual, int index) {
        StringBuilder out = new StringBuilder("    first difference at line ")
                .append(index + 1)
                .append(System.lineSeparator());
        for (int i = Math.max(0, index - 2); i < index; i++) {
            out.append("      ").append(expected.get(i)).append(System.lineSeparator());
        }
        out.append("    - reference: ")
                .append(expected.get(index))
                .append(System.lineSeparator())
                .append("    + generated: ")
                .append(actual.get(index))
                .append(System.lineSeparator());
        return out.toString();
    }

    private static List<String> lines(byte[] content) {
        return new String(content, StandardCharsets.UTF_8).lines().toList();
    }

    private static Map<String, byte[]> readTree(Path root, List<String> excluded) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(Files::isRegularFile)
                    .map(path -> Map.entry(root.relativize(path).toString().replace('\\', '/'), path))
                    .filter(entry -> excluded.stream().noneMatch(rule -> matches(entry.getKey(), rule)))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> files.put(entry.getKey(), read(entry.getValue())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new TreeMap<>(files);
    }

    /**
     * Three forms, deliberately no more: a directory prefix, a {@code *.suffix}, or an exact
     * path. §4's ignore list has to be short and readable — a glob language here would make it
     * possible to exclude something without anybody noticing what.
     */
    private static boolean matches(String path, String rule) {
        if (rule.endsWith("/")) {
            return path.startsWith(rule) || path.contains("/" + rule);
        }
        if (rule.startsWith("*.")) {
            return path.endsWith(rule.substring(1));
        }
        return path.equals(rule);
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String describe(Path referenceProject) {
        return referenceProject.getFileName()
                + " — edit the reference project, run this test, and port the diff into the recipe"
                + " (its REFERENCE.md explains the workflow)";
    }
}
