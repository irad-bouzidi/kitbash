package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the ignore list short, and honest.
 *
 * <p>§4 asks for an ignore list that is explicit, short and documented — "anything excluded must be
 * named and justified, not silently skipped". A document saying so is a request; this is the part
 * that makes it true. An exclusion nobody wrote down fails the build, which is the only way a list
 * like this stays a list rather than becoming a habit.
 *
 * <p>It is also the cheapest of the three checks: no rendering, no comparison, two files read.
 */
class ReferenceIgnoreListTest {

    private static final Path DOCUMENTATION = Path.of("docs", "reference-projects.md");

    @Test
    @DisplayName("every excluded path is justified in docs/reference-projects.md")
    void everyExclusionIsDocumented() {
        String documentation = read(ReferenceProjects.repositoryRoot().resolve(DOCUMENTATION));

        for (Path referenceProject : ReferenceProjects.referenceProjects()) {
            // Asserting on the undocumented rules rather than on the document keeps the failure
            // readable: what a maintainer needs is the one rule to write a row for, not the
            // eight thousand characters it is not in.
            List<String> undocumented = ReferenceProjects.excluded(referenceProject).stream()
                    .filter(rule -> !documentation.contains("`" + rule + "`"))
                    .toList();

            assertThat(undocumented)
                    .as(
                            "%s excludes these paths from the equality test and %s does not say why. "
                                    + "Add a row justifying each, or stop excluding it — a silent skip is "
                                    + "how a reference project stops being the thing we maintain.",
                            referenceProject.getFileName(), DOCUMENTATION)
                    .isEmpty();
        }
    }

    /**
     * Short, per §4. The number is not sacred — the point is that growing the list past it is a
     * decision somebody makes on purpose rather than a line added to make a test pass.
     */
    @Test
    @DisplayName("the list stays short enough to read")
    void staysShort() {
        for (Path referenceProject : ReferenceProjects.referenceProjects()) {
            assertThat(ReferenceProjects.excluded(referenceProject))
                    .as("%s", referenceProject.getFileName())
                    .hasSizeLessThanOrEqualTo(10);
        }
    }

    /**
     * Three forms and no more, matching what the comparison actually implements. A rule written in
     * a form the matcher does not understand excludes nothing, and the symptom would be a test
     * failure about a file somebody believed was already ignored.
     */
    @Test
    @DisplayName("every rule is a directory prefix, a suffix, or an exact path")
    void usesOnlyTheThreeForms() {
        for (Path referenceProject : ReferenceProjects.referenceProjects()) {
            assertThat(ReferenceProjects.excluded(referenceProject))
                    .as("%s", referenceProject.getFileName())
                    .allSatisfy(rule -> assertThat(rule.endsWith("/") || rule.startsWith("*.") || !rule.contains("*"))
                            .as("'%s' is not a directory prefix, a *.suffix or an exact path", rule)
                            .isTrue());
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
