package dev.kitbash.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.selection.OptionValue;
import dev.kitbash.core.selection.Selection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * §17: <i>build the resolver test suite in phase 1 and keep it fast and pure. It is the component
 * with real logic and the one where regressions stay invisible until a user's project fails to
 * compile.</i>
 *
 * <p>Two tables carry most of it — one of combinations that must resolve, one of pairings that must
 * each produce exactly one diagnostic — so adding a recipe later means adding rows rather than
 * writing new test classes. Nothing here touches a filesystem or builds a context.
 */
class ResolverTest {

    private static final Catalog CATALOG = TestCatalog.v1();

    private static Selection selection(Object... optionPairs) {
        Map<String, OptionValue> options = new LinkedHashMap<>();
        for (int i = 0; i < optionPairs.length; i += 2) {
            Object value = optionPairs[i + 1];
            options.put(
                    (String) optionPairs[i],
                    value instanceof Boolean flag ? OptionValue.flag(flag) : OptionValue.text((String) value));
        }
        return new Selection("customer-management", options, Map.of("groupId", "com.acme"));
    }

    private static List<String> ids(Resolution resolution) {
        return resolution.recipes().stream().map(recipe -> recipe.id().value()).toList();
    }

    // --- the table of things that must resolve -------------------------------

    static Stream<Arguments> validCombinations() {
        return Stream.of(
                Arguments.of(
                        "backend only",
                        selection("backend", "backend-spring-java", "buildTool", "build-gradle-kts"),
                        List.of("base", "build-gradle-kts", "backend-spring-java", "db-postgres-flyway")),
                Arguments.of(
                        "frontend only",
                        selection("frontend", "frontend-react-vite"),
                        List.of("base", "frontend-react-vite")),
                Arguments.of(
                        "full stack",
                        selection(
                                "backend", "backend-spring-java",
                                "buildTool", "build-gradle-kts",
                                "frontend", "frontend-react-vite"),
                        List.of(
                                "base",
                                "build-gradle-kts",
                                "backend-spring-java",
                                "frontend-react-vite",
                                "db-postgres-flyway")),
                Arguments.of(
                        "full stack with containers and CI",
                        selection(
                                "backend", "backend-spring-java",
                                "buildTool", "build-maven",
                                "frontend", "frontend-react-vite",
                                "ci", "ci-gitlab",
                                "docker", true),
                        List.of(
                                "base",
                                "build-maven",
                                "backend-spring-java",
                                "frontend-react-vite",
                                "db-postgres-flyway",
                                "infra-docker",
                                "ci-gitlab")),
                Arguments.of(
                        "the Kotlin backend, to prove neither backend is special",
                        selection("backend", "backend-spring-kotlin", "buildTool", "build-gradle-kts"),
                        List.of("base", "build-gradle-kts", "backend-spring-kotlin", "db-postgres-flyway")),
                Arguments.of(
                        "a feature that requires the backend",
                        selection(
                                "backend", "backend-spring-java",
                                "buildTool", "build-gradle-kts",
                                "auth", "feature-auth-jwt"),
                        List.of(
                                "base",
                                "build-gradle-kts",
                                "backend-spring-java",
                                "db-postgres-flyway",
                                "feature-auth-jwt")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validCombinations")
    @DisplayName("every enumerated v1 combination resolves cleanly, in pipeline order")
    void resolvesCleanly(String name, Selection selection, List<String> expected) {
        Resolution resolution = Resolver.resolve(CATALOG, selection);

        assertThat(resolution.conflicts()).isEmpty();
        assertThat(resolution.valid()).isTrue();
        assertThat(ids(resolution)).containsExactlyElementsOf(expected);
    }

    // --- the table of things that must not ----------------------------------

    static Stream<Arguments> invalidCombinations() {
        return Stream.of(
                Arguments.of(
                        "two backends that declare each other a conflict",
                        new Selection(
                                "svc",
                                Map.of(
                                        "backend",
                                                OptionValue.multi(
                                                        List.of("backend-spring-java", "backend-spring-kotlin")),
                                        "buildTool", OptionValue.text("build-gradle-kts")),
                                Map.of()),
                        ErrorCode.CONFLICT,
                        "backend"),
                Arguments.of(
                        "a backend with no build tool chosen, and two to choose from",
                        selection("backend", "backend-spring-java"),
                        ErrorCode.CAPABILITY_UNSATISFIED,
                        "build-tool"),
                Arguments.of(
                        "an architecture value that is not in the enum",
                        selection(
                                "backend", "backend-spring-java",
                                "buildTool", "build-gradle-kts",
                                "architecture", "clean"),
                        ErrorCode.INVALID_IDENTIFIER,
                        "architecture"),
                Arguments.of(
                        "a boolean option given a string",
                        new Selection(
                                "svc",
                                Map.of(
                                        "frontend", OptionValue.text("frontend-react-vite"),
                                        "typedClient", OptionValue.text("yes")),
                                Map.of()),
                        ErrorCode.INVALID_IDENTIFIER,
                        "typedClient"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCombinations")
    @DisplayName("every invalid pairing produces exactly one diagnostic, naming the option to change")
    void producesOneDiagnostic(String name, Selection selection, ErrorCode expectedCode, String expectedOption) {
        Resolution resolution = Resolver.resolve(CATALOG, selection);

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.conflicts()).hasSize(1);
        GenerationError error = resolution.firstConflict();
        assertThat(error.code()).isEqualTo(expectedCode);
        // §8: the diagnostic names the option the user can change, not the capability the engine
        // rejected. "Auth requires a backend; select one" beats "capability http-server unsatisfied".
        assertThat(error.hint() + " " + error.message()).contains(expectedOption);
    }

    // --- implied expansion ---------------------------------------------------

    @Nested
    @DisplayName("implied expansion")
    class Implied {

        @Test
        @DisplayName("a capability with exactly one provider is selected automatically")
        void singleProviderIsImplied() {
            Resolution resolution = Resolver.resolve(
                    CATALOG, selection("backend", "backend-spring-java", "buildTool", "build-gradle-kts"));

            assertThat(resolution.implied())
                    .extracting(RecipeId::value)
                    .containsExactlyInAnyOrder("base", "db-postgres-flyway");
            // §9: the right rail shows the resolved stack including what the resolver added, so
            // "implied" has to be reported rather than silently folded into the list.
            assertThat(ids(resolution)).contains("db-postgres-flyway");
        }

        @Test
        @DisplayName("a capability with several providers is a choice, never a guess")
        void severalProvidersIsAChoice() {
            Resolution resolution = Resolver.resolve(CATALOG, selection("backend", "backend-spring-java"));

            assertThat(resolution.conflicts()).hasSize(1);
            assertThat(resolution.firstConflict().hint())
                    .contains("build-gradle-kts")
                    .contains("build-maven");
            assertThat(resolution.implied()).extracting(RecipeId::value).doesNotContain("build-maven");
        }

        @Test
        @DisplayName("an ambiguous capability is reported once, not once per expansion pass")
        void ambiguityIsReportedOnce() {
            Resolution resolution = Resolver.resolve(
                    CATALOG, selection("backend", "backend-spring-java", "frontend", "frontend-react-vite"));

            assertThat(resolution.conflicts()).hasSize(1);
        }

        @Test
        @DisplayName("a true boolean naming a capability pulls in its provider")
        void booleanOptionDemandsACapability() {
            Resolution resolution =
                    Resolver.resolve(CATALOG, selection("frontend", "frontend-react-vite", "docker", true));

            assertThat(ids(resolution)).contains("infra-docker");
            assertThat(resolution.implied()).extracting(RecipeId::value).contains("infra-docker");
        }

        @Test
        @DisplayName("a false boolean pulls in nothing")
        void falseBooleanDemandsNothing() {
            Resolution resolution =
                    Resolver.resolve(CATALOG, selection("frontend", "frontend-react-vite", "docker", false));

            assertThat(ids(resolution)).doesNotContain("infra-docker");
        }
    }

    // --- ordering and determinism -------------------------------------------

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("is base → backend → frontend → feature → infra → CI, ties by recipe id")
        void followsPipelineOrder() {
            Resolution resolution = Resolver.resolve(
                    CATALOG,
                    selection(
                            "backend", "backend-spring-java",
                            "buildTool", "build-gradle-kts",
                            "frontend", "frontend-react-vite",
                            "ci", "ci-gitlab",
                            "docker", true));

            assertThat(resolution.recipes()).extracting(Recipe::kind).isSorted();
            // base and build-gradle-kts are both BASE and independent of each other, so the tie is
            // broken by id — which is what makes the order total and the zip byte-identical.
            assertThat(ids(resolution)).startsWith("base", "build-gradle-kts");
        }

        @Test
        @DisplayName("a recipe never precedes one providing what it requires")
        void respectsDependencies() {
            Resolution resolution = Resolver.resolve(
                    CATALOG,
                    selection(
                            "backend",
                            "backend-spring-java",
                            "buildTool",
                            "build-gradle-kts",
                            "auth",
                            "feature-auth-jwt"));

            List<String> order = ids(resolution);
            // db-postgres-flyway is a FEATURE that the BACKEND requires, so kind order alone would
            // put it in the wrong place. The dependency edge is what corrects it.
            assertThat(order.indexOf("db-postgres-flyway")).isGreaterThan(order.indexOf("backend-spring-java"));
            assertThat(order.indexOf("feature-auth-jwt")).isGreaterThan(order.indexOf("backend-spring-java"));
        }

        @Test
        @DisplayName("is identical however the caller ordered the options")
        void isIndependentOfSelectionOrder() {
            Map<String, OptionValue> oneWay = new LinkedHashMap<>();
            oneWay.put("ci", OptionValue.text("ci-gitlab"));
            oneWay.put("backend", OptionValue.text("backend-spring-java"));
            oneWay.put("buildTool", OptionValue.text("build-gradle-kts"));
            oneWay.put("frontend", OptionValue.text("frontend-react-vite"));

            Map<String, OptionValue> theOther = new LinkedHashMap<>();
            theOther.put("frontend", OptionValue.text("frontend-react-vite"));
            theOther.put("buildTool", OptionValue.text("build-gradle-kts"));
            theOther.put("backend", OptionValue.text("backend-spring-java"));
            theOther.put("ci", OptionValue.text("ci-gitlab"));

            assertThat(ids(Resolver.resolve(CATALOG, new Selection("svc", oneWay, Map.of()))))
                    .isEqualTo(ids(Resolver.resolve(CATALOG, new Selection("svc", theOther, Map.of()))));
        }

        @Test
        @DisplayName("names the members of a cycle, because 'cycle detected' is not a diagnosis")
        void detectsAndNamesCycles() {
            Catalog knot = Catalog.of(
                    List.of(
                            TestCatalog.recipe(
                                    "feature-a",
                                    RecipeKind.FEATURE,
                                    TestCatalog.provides("a"),
                                    TestCatalog.requires("b")),
                            TestCatalog.recipe(
                                    "feature-b",
                                    RecipeKind.FEATURE,
                                    TestCatalog.provides("b"),
                                    TestCatalog.requires("a"))),
                    "sha256:knot");

            Resolution resolution = Resolver.resolve(
                    knot,
                    new Selection(
                            "svc", Map.of("features", OptionValue.multi(List.of("feature-a", "feature-b"))), Map.of()));

            assertThat(resolution.conflicts()).hasSize(1);
            assertThat(resolution.firstConflict().code()).isEqualTo(ErrorCode.CYCLE);
            assertThat(resolution.firstConflict().message())
                    .contains("feature-a")
                    .contains("feature-b");
            // Even when the graph is broken, a stack comes back so the caller has something to show.
            assertThat(resolution.recipes()).hasSize(2);
        }
    }

    // --- the payload /validate returns ---------------------------------------

    @Nested
    @DisplayName("the resolution payload")
    class Payload {

        @Test
        @DisplayName("applies catalog defaults to the effective option set")
        void appliesDefaults() {
            Resolution resolution = Resolver.resolve(
                    CATALOG, selection("backend", "backend-spring-java", "buildTool", "build-gradle-kts"));

            assertThat(resolution.effectiveOptions()).containsEntry("architecture", OptionValue.text("layered"));
        }

        @Test
        @DisplayName("keeps a supplied value over the default")
        void suppliedValueWins() {
            Resolution resolution = Resolver.resolve(
                    CATALOG,
                    selection(
                            "backend", "backend-spring-java",
                            "buildTool", "build-gradle-kts",
                            "architecture", "hexagonal"));

            assertThat(resolution.effectiveOptions()).containsEntry("architecture", OptionValue.text("hexagonal"));
        }

        @Test
        @DisplayName("warns about an option nothing reads, rather than blocking on it")
        void warnsAboutOrphanedOptions() {
            Resolution resolution = Resolver.resolve(
                    CATALOG,
                    selection(
                            "backend", "backend-spring-java",
                            "buildTool", "build-gradle-kts",
                            "somethingFromThePast", "yes"));

            assertThat(resolution.valid()).isTrue();
            assertThat(resolution.warnings()).singleElement().satisfies(warning -> assertThat(warning.optionId())
                    .isEqualTo("somethingFromThePast"));
        }

        @Test
        @DisplayName("reports the capabilities the stack provides, which is what when-expressions read")
        void reportsCapabilities() {
            Resolution resolution = Resolver.resolve(
                    CATALOG,
                    selection(
                            "backend",
                            "backend-spring-java",
                            "buildTool",
                            "build-gradle-kts",
                            "frontend",
                            "frontend-react-vite"));

            assertThat(resolution.capabilities())
                    .contains(Capability.of("rest-api"), Capability.of("spa"), Capability.of("database"));
            assertThat(resolution.whenContext().capabilities()).isEqualTo(resolution.capabilities());
        }

        @Test
        @DisplayName("locks exactly the recipes it resolved, against the catalog that resolved them")
        void producesALock() {
            Resolution resolution = Resolver.resolve(
                    CATALOG, selection("backend", "backend-spring-java", "buildTool", "build-gradle-kts"));

            assertThat(resolution.lock("sha256:test").recipeVersions())
                    .hasSize(resolution.recipes().size());
            assertThat(resolution.lock("sha256:test").coordinates()).contains("backend-spring-java@1.0.0");
        }

        @Test
        @DisplayName("is a pure function: same input, same output, no matter how often it is called")
        void isPure() {
            Selection selection =
                    selection("backend", "backend-spring-java", "buildTool", "build-gradle-kts", "docker", true);

            Resolution first = Resolver.resolve(CATALOG, selection);
            Resolution second = Resolver.resolve(CATALOG, selection);

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("stays cheap enough to call on every option change")
        void isCheapEnoughToCallConstantly() {
            // §8 has the wizard calling /validate on every debounced change, and §6 makes that
            // affordable by running only stages 1-2. The guard is against the regression that
            // would quietly end that: something in here reaching for a file, a clock or a lock.
            Selection selection = selection(
                    "backend", "backend-spring-java",
                    "buildTool", "build-gradle-kts",
                    "frontend", "frontend-react-vite",
                    "ci", "ci-gitlab",
                    "docker", true);
            Resolver.resolve(CATALOG, selection);

            long startedAt = System.nanoTime();
            for (int i = 0; i < 10_000; i++) {
                Resolver.resolve(CATALOG, selection);
            }
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(millis)
                    .withFailMessage(
                            "10,000 resolutions took %dms; the resolver has stopped being pure or cheap", millis)
                    .isLessThan(2_000);
        }

        @Test
        @DisplayName("an empty selection resolves to nothing at all, rather than guessing a stack")
        void emptySelectionResolvesToNothing() {
            Resolution resolution = Resolver.resolve(CATALOG, new Selection("svc", Map.of(), Map.of()));

            assertThat(resolution.recipes()).isEmpty();
            assertThat(resolution.conflicts()).isEmpty();
            assertThat(resolution.capabilities()).isEqualTo(Set.of());
        }
    }
}
