package dev.kitbash.catalog.contributed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.PatchRule;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two controls the sandbox cannot provide (threat model §3.4, §3.5).
 *
 * <p>Worth more scrutiny than the sandbox tests, not less. The sandbox defends against a threat
 * whose severity the threat model rates High at worst; these two defend against both of the
 * Criticals, and unlike the sandbox they are a handful of string comparisons — which is exactly
 * the kind of control that erodes without anybody noticing.
 */
class ContributedRecipeRulesTest {

    private static final Set<String> ALLOWED =
            Set.of("com.fasterxml.jackson.core:jackson-databind", "org.springframework.boot:spring-boot-starter-web");

    private final ContributedRecipeRules rules = new ContributedRecipeRules(ALLOWED);

    private static Recipe recipe(Set<Capability> provides, List<PatchRule> patches) {
        return new Recipe(
                RecipeId.of("@platform/audit-log"),
                RecipeVersion.parse("1.0.0"),
                null,
                RecipeKind.FEATURE,
                "Audit log",
                provides,
                Set.of(),
                Set.of(),
                List.of(),
                Set.of(),
                List.of(),
                patches,
                false,
                null);
    }

    private static PatchRule dependency(String coordinate) {
        return PatchRule.always(
                new PatchOp.AddDependency(RecipeId.of("@platform/audit-log"), ".", "implementation", coordinate, null));
    }

    private static PatchRule mergeYaml(String target) {
        return PatchRule.always(
                new PatchOp.MergeYaml(RecipeId.of("@platform/audit-log"), target, java.util.Map.of("on", "push")));
    }

    @Test
    @DisplayName("an ordinary contributed recipe passes, or the refusals below mean nothing")
    void allowsWhatItShould() {
        assertThat(rules.refusalsFor(
                        recipe(Set.of(), List.of(dependency("com.fasterxml.jackson.core:jackson-databind")))))
                .isEmpty();
    }

    @Nested
    @DisplayName("dependency coordinates (§3.4)")
    class Coordinates {

        @Test
        @DisplayName("the allowlist ignores the version, because pinning it would make this list rot")
        void versionIsNotPartOfTheMatch() {
            assertThat(rules.refusalsFor(recipe(
                            Set.of(), List.of(dependency("com.fasterxml.jackson.core:jackson-databind:2.19.2")))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a typosquat one character from a real coordinate is refused")
        void refusesATyposquat() {
            // The attack this control exists for. Neither the osv-scanner pass nor a human
            // skim-reading a manifest reliably catches it: the package is not in any advisory
            // database, and the difference is a missing '.core'.
            assertThat(rules.refusalsFor(
                            recipe(Set.of(), List.of(dependency("com.fasterxml.jackson:jackson-databind")))))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("not on the allowlist")
                    .contains("com.fasterxml.jackson:jackson-databind");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "evil", // one segment: must not normalise into something that matches
                    "com.evil:backdoor",
                    ":jackson-databind",
                    "com.fasterxml.jackson.core:jackson-databind-extra",
                    "com.fasterxml.jackson.core.:jackson-databind",
                    "COM.FASTERXML.JACKSON.CORE:jackson-databind"
                })
        @DisplayName("nothing off the list gets through, however close it looks")
        void refusesEverythingElse(String coordinate) {
            assertThat(rules.refusalsFor(recipe(Set.of(), List.of(dependency(coordinate)))))
                    .hasSize(1);
        }

        @Test
        @DisplayName("every bad coordinate is reported, not just the first")
        void reportsAllOfThem() {
            // A contributor who fixes one and resubmits to be told about the next has been given
            // a guessing game, and a reviewer cannot tell how much is wrong with a submission
            // that reported one problem.
            assertThat(rules.refusalsFor(
                            recipe(Set.of(), List.of(dependency("com.evil:one"), dependency("com.evil:two")))))
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("CI (§3.5)")
    class ContinuousIntegration {

        @Test
        @DisplayName("declaring the ci capability is refused, with the reason rather than the rule")
        void refusesTheCapability() {
            assertThat(rules.refusalsFor(recipe(Set.of(Capability.of("ci")), List.of())))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("runs in")
                    .contains("secrets");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    ".github/workflows/ci.yml",
                    ".gitlab-ci.yml",
                    "Jenkinsfile",
                    ".circleci/config.yml",
                    "azure-pipelines.yml",
                    ".GitHub/workflows/ci.yml"
                })
        @DisplayName("patching a CI file is refused even from a recipe declaring no capability")
        void refusesTheTargetToo(String target) {
            // Blocking only the capability would block only the honest attacker: a mergeYaml
            // straight at a workflow file from a recipe that declares nothing is the other route,
            // and it is the one somebody would actually take.
            assertThat(rules.refusalsFor(recipe(Set.of(), List.of(mergeYaml(target)))))
                    .hasSize(1);
        }

        @Test
        @DisplayName("an ordinary YAML target is untouched by the CI rule")
        void leavesOtherYamlAlone() {
            assertThat(rules.refusalsFor(recipe(Set.of(), List.of(mergeYaml("src/main/resources/application.yml")))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the boundary that matters most")
    class Hooks {

        @Test
        @DisplayName("a contributed recipe may not declare a hook")
        void refusesAHook() {
            // §47: "that boundary is the main thing standing between this feature and an
            // arbitrary-code-execution endpoint."
            Recipe withHook = new Recipe(
                    RecipeId.of("@platform/audit-log"),
                    RecipeVersion.parse("1.0.0"),
                    null,
                    RecipeKind.FEATURE,
                    "Audit log",
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    List.of(),
                    Set.of(),
                    List.of(),
                    List.of(),
                    true,
                    null);

            assertThat(rules.refusalsFor(withHook))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("hook code is registered in the application");
        }
    }

    @Test
    @DisplayName("an unnamespaced id is refused, so §6.3 cannot be skipped by submitting one")
    void refusesAnUnnamespacedId() {
        Recipe shipped = new Recipe(
                RecipeId.of("audit-log"),
                RecipeVersion.parse("1.0.0"),
                null,
                RecipeKind.FEATURE,
                "Audit log",
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                false,
                null);

        assertThat(rules.refusalsFor(shipped))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("must be namespaced");
    }
}
