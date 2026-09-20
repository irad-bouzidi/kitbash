package dev.kitbash.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The rule that lets one recipe hold several alternative trees: a glob's literal prefix is the
 * recipe's own layout, not the project's.
 *
 * <p>{@code from: files/**} and {@code from: arch/hexagonal/**} both land at the project root, so a
 * recipe can carry {@code arch/layered/} and {@code arch/hexagonal/} side by side and pick between
 * them with {@code when} — which is what stops "architecture" from meaning a second recipe.
 */
class PlannerPrefixTest {

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
        "files/**, files/src/Main.java, src/Main.java",
        "arch/hexagonal/**, arch/hexagonal/src/Main.java, src/Main.java",
        "files/*.md, files/README.md, README.md",
        "files/**/*.java, files/src/Main.java, src/Main.java",
        "**, src/Main.java, src/Main.java",
    })
    @DisplayName("strips the literal part of the glob, and nothing more")
    void stripsTheLiteralPrefix(String glob, String path, String expected) {
        assertThat(Planner.stripPrefix(glob, path)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a path that does not start with the prefix is left alone rather than mangled")
    void leavesUnmatchedPathsAlone() {
        assertThat(Planner.stripPrefix("files/**", "elsewhere/README.md")).isEqualTo("elsewhere/README.md");
    }
}
