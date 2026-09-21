package dev.kitbash.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.hostile.HostileInputs;
import dev.kitbash.core.plan.Caps;
import dev.kitbash.core.plan.SafePaths;
import dev.kitbash.core.selection.Identifiers;
import dev.kitbash.core.workspace.Workspace;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every input is hostile (§13).
 *
 * <p>The generator takes user strings and writes them into files somebody will then execute. That
 * one sentence is the whole justification for this file: not that an attack is expected, but that
 * the output is code, and code assembled from unvalidated input is the oldest mistake there is.
 *
 * <p>The corpus lives in {@link HostileInputs} so {@code kitbash-39} and {@code kitbash-47} test
 * their own surfaces against the same values rather than against a copy of them.
 */
class InputValidationTest {

    static List<HostileInputs.Case> hostileIdentifiers() {
        return HostileInputs.identifiers();
    }

    static List<HostileInputs.Case> hostileProjectNames() {
        return HostileInputs.projectNames();
    }

    static List<HostileInputs.Case> hostilePaths() {
        return HostileInputs.paths();
    }

    @Nested
    @DisplayName("identifiers")
    class Identifier {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.kitbash.core.InputValidationTest#hostileIdentifiers")
        @DisplayName("a hostile package name is refused, as INVALID_IDENTIFIER")
        void refusesHostilePackageNames(HostileInputs.Case hostile) {
            assertThatThrownBy(() -> Identifiers.requirePackageName(hostile.value()))
                    .isInstanceOf(GenerationException.class)
                    .extracting(thrown -> ((GenerationException) thrown).error().code())
                    .isEqualTo(ErrorCode.INVALID_IDENTIFIER);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.kitbash.core.InputValidationTest#hostileProjectNames")
        @DisplayName("a hostile project name is refused, as INVALID_IDENTIFIER")
        void refusesHostileProjectNames(HostileInputs.Case hostile) {
            assertThatThrownBy(() -> Identifiers.requireProjectName(hostile.value()))
                    .isInstanceOf(GenerationException.class)
                    .extracting(thrown -> ((GenerationException) thrown).error().code())
                    .isEqualTo(ErrorCode.INVALID_IDENTIFIER);
        }

        /**
         * The other half of the contract. A validator with no positive cases drifts towards
         * rejecting everything, because every rejection makes some test greener.
         */
        @Test
        @DisplayName("the identifiers real projects use keep working")
        void acceptsRealIdentifiers() {
            HostileInputs.acceptableIdentifiers()
                    .forEach(value -> assertThatCode(() -> Identifiers.requirePackageName(value))
                            .as("%s", value)
                            .doesNotThrowAnyException());
            HostileInputs.acceptableProjectNames()
                    .forEach(value -> assertThatCode(() -> Identifiers.requireProjectName(value))
                            .as("%s", value)
                            .doesNotThrowAnyException());
        }

        /**
         * §13 says reject, never sanitize — and the reason is in this assertion. A stripped value
         * would come back as a different, valid package name, and the user would find out when an
         * import did not resolve.
         */
        @Test
        @DisplayName("a bad value is rejected whole; nothing is stripped and quietly accepted")
        void refusesRatherThanSanitizing() {
            assertThatThrownBy(() -> Identifiers.requirePackageName("com.example; rm -rf /"))
                    .isInstanceOf(GenerationException.class)
                    .hasMessageContaining("com.example; rm -rf /");
        }

        @Test
        @DisplayName("the message carries the rule and the offending value, so the fix is obvious")
        void saysWhatIsWrong() {
            assertThatThrownBy(() -> Identifiers.requirePackageName("com.fun.thing"))
                    .hasMessageContaining("Kotlin keyword 'fun'")
                    .hasMessageContaining("com.fun.thing");
        }

        /**
         * The Kotlin-only half of the keyword rule, asserted one keyword at a time.
         *
         * <p>kitbash-20 added this list before there was a Kotlin backend, on §13's reasoning that
         * a package accepted today survives in somebody's preset and share link. kitbash-29 added
         * the backend, which turns the list from a prediction into a contract: each of these is a
         * package segment that `kotlinc` refuses outright, and the ones below are exactly the ones
         * Java's own keyword list does *not* already catch — so a regression here would be silent
         * against the Java tests and loud against a generated Kotlin project.
         */
        @ParameterizedTest(name = "com.{0}.thing")
        @ValueSource(strings = {"fun", "val", "var", "object", "when", "is", "in", "typealias"})
        @DisplayName("a Kotlin-only keyword is refused as a package segment")
        void refusesKotlinOnlyKeywords(String keyword) {
            assertThatCode(() -> Identifiers.requirePackageName("com.ok" + keyword + ".thing"))
                    .as("only the whole segment is a keyword; '%s' inside a longer word is fine", keyword)
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> Identifiers.requirePackageName("com." + keyword + ".thing"))
                    .isInstanceOf(GenerationException.class)
                    .hasMessageContaining("Kotlin keyword '" + keyword + "'");
        }
    }

    @Nested
    @DisplayName("paths")
    class Paths {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.kitbash.core.InputValidationTest#hostilePaths")
        @DisplayName("a hostile rendered path is refused, as PATH_ESCAPE")
        void refusesHostilePaths(HostileInputs.Case hostile) {
            assertThatThrownBy(() -> SafePaths.require("recipe-under-test", hostile.value()))
                    .isInstanceOf(GenerationException.class)
                    .extracting(thrown -> ((GenerationException) thrown).error().code())
                    .isEqualTo(ErrorCode.PATH_ESCAPE);
        }

        @Test
        @DisplayName("the paths a real project contains keep working")
        void acceptsRealPaths() {
            HostileInputs.acceptablePaths()
                    .forEach(path -> assertThatCode(() -> SafePaths.require("recipe-under-test", path))
                            .as("%s", path)
                            .doesNotThrowAnyException());
        }

        /**
         * The plan stage sees paths that still carry their placeholders, so it judges structure
         * only — {@code {{ packageName | packagePath }}} contains a pipe, which no file name may.
         * Checking characters there would refuse every templated path in the catalog.
         */
        @Test
        @DisplayName("a template path keeps its placeholders, and is still checked for traversal")
        void checksTemplatePathsStructurally() {
            assertThatCode(() -> SafePaths.requireTemplatePath(
                            "backend", "src/main/java/{{ packageName | packagePath }}/Thing.java.peb"))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> SafePaths.requireTemplatePath("backend", "../{{ packageName }}/Thing.java.peb"))
                    .isInstanceOf(GenerationException.class)
                    .hasMessageContaining("traverse");
        }

        @Test
        @DisplayName("the error names the recipe, because a bad path is a recipe's bug, not a user's")
        void namesTheRecipe() {
            assertThatThrownBy(() -> SafePaths.require("backend-spring-java", "../escape.txt"))
                    .isInstanceOf(GenerationException.class)
                    .extracting(thrown -> ((GenerationException) thrown).error().recipe())
                    .isEqualTo("backend-spring-java");
        }

        /**
         * Two paths differing only by case coexist on Linux and collide everywhere else. Only the
         * workspace can catch it, because it is the only layer that has seen both.
         */
        @Test
        @DisplayName("two paths differing only by case are refused, because one would replace the other")
        void refusesCaseCollisions() {
            Workspace workspace = new Workspace();
            workspace.putText("src/Thing.java", "one");

            assertThatThrownBy(() -> workspace.putText("src/thing.java", "two"))
                    .isInstanceOf(GenerationException.class)
                    .hasMessageContaining("differs only by case");

            // Rewriting the same path is not a collision: patches do it constantly.
            assertThatCode(() -> workspace.putText("src/Thing.java", "one, patched"))
                    .doesNotThrowAnyException();
            assertThat(workspace.fileCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("caps")
    class ResourceCaps {

        /**
         * The wall clock is the one cap that cannot be read off the plan: it is a property of the
         * run, not of the selection. An already-expired deadline is how a test trips it without
         * waiting ten seconds for the real one.
         */
        @Test
        @DisplayName("an expired deadline stops the run, naming the stage it died in")
        void enforcesTheWallClock() {
            Caps.Deadline expired = new Caps(1, 1, 1, Duration.ofNanos(-1)).deadline();

            assertThatThrownBy(() -> expired.check("render"))
                    .isInstanceOf(GenerationException.class)
                    .hasMessageContaining("wall clock")
                    .hasMessageContaining("render")
                    .extracting(thrown -> ((GenerationException) thrown).error().code())
                    .isEqualTo(ErrorCode.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("a deadline with budget left says nothing")
        void allowsAFastRun() {
            assertThatCode(() -> Caps.standard().deadline().check("render")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the §13 numbers are the ones actually configured")
        void carriesThePlansNumbers() {
            Caps standard = Caps.standard();

            assertThat(standard.maxFiles()).isEqualTo(5_000);
            assertThat(standard.maxTotalBytes()).isEqualTo(50L * 1024 * 1024);
            assertThat(standard.maxFileBytes()).isEqualTo(5L * 1024 * 1024);
            assertThat(standard.wallClock()).isEqualTo(Duration.ofSeconds(10));
        }
    }
}
