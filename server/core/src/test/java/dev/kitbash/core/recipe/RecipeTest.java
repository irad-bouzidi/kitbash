package dev.kitbash.core.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.selection.OptionValue;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecipeTest {

    @ParameterizedTest
    @CsvSource({"1.0.0, 1.0.1, -1", "1.2.0, 1.10.0, -1", "2.0.0, 1.99.99, 1", "1.4.0, 1.4.0, 0"})
    @DisplayName("recipe versions order numerically, not lexically")
    void versionsCompareNumerically(String left, String right, int expected) {
        assertThat(Integer.signum(RecipeVersion.parse(left).compareTo(RecipeVersion.parse(right))))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.4", "1.4.0.0", "v1.4.0", "01.4.0", "1.4.0-rc1", ""})
    @DisplayName("anything that is not plain MAJOR.MINOR.PATCH is refused at the door")
    void refusesNonSemver(String value) {
        // Pre-release and build metadata are unsupported on purpose: a catalog shipped from git has
        // no use for them, and each one is another ordering rule to get wrong.
        assertThatThrownBy(() -> RecipeVersion.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Backend", "backend_spring", "-backend", "backend-", "1backend", "backend--spring"})
    @DisplayName("recipe ids are kebab-case because they are also directory names")
    void refusesMalformedRecipeIds(String value) {
        assertThatThrownBy(() -> RecipeId.of(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an option that accepts a value of the wrong shape would be a broken wizard control")
    void optionSpecChecksShapeAndDomain() {
        OptionSpec architecture = OptionSpec.of(
                "architecture",
                OptionType.ENUM,
                List.of("layered", "hexagonal"),
                OptionValue.text("layered"),
                "Architecture",
                "Determines the package layout and dependency direction.");

        assertThat(architecture.accepts(OptionValue.text("hexagonal"))).isTrue();
        assertThat(architecture.accepts(OptionValue.text("clean"))).isFalse();
        assertThat(architecture.accepts(OptionValue.flag(true))).isFalse();
        assertThat(architecture.accepts(OptionValue.multi(List.of("layered")))).isFalse();
    }

    @Test
    @DisplayName("an option with no help text is refused, so no option can arrive unexplained")
    void optionsMustCarryHelp() {
        assertThatThrownBy(() ->
                        OptionSpec.of("docker", OptionType.BOOLEAN, List.of(), OptionValue.flag(true), "Docker", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("help");
    }

    @Test
    @DisplayName("an enum option with no values would render as an empty dropdown")
    void enumOptionsMustDeclareValues() {
        assertThatThrownBy(() -> OptionSpec.of(
                        "architecture", OptionType.ENUM, List.of(), OptionValue.text("layered"), "Arch", "help"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must declare values");
    }

    @Test
    @DisplayName("declaration order in a manifest does not reach the catalog digest")
    void capabilitiesAreStoredSorted() {
        Set<Capability> asWritten = new LinkedHashSet<>();
        asWritten.add(Capability.of("rest-api"));
        asWritten.add(Capability.of("http-server"));
        asWritten.add(Capability.of("jvm-project"));

        Recipe recipe = backend(asWritten);

        assertThat(recipe.provides())
                .extracting(Capability::name)
                .containsExactly("http-server", "jvm-project", "rest-api");
    }

    @Test
    @DisplayName("kind order is the §4 pipeline order, so the enum is the sort key")
    void kindOrderIsPipelineOrder() {
        assertThat(RecipeKind.values())
                .extracting(RecipeKind::wireName)
                .containsExactly("base", "backend", "frontend", "mobile", "feature", "infra", "ci");
    }

    @Test
    @DisplayName("a recipe's coordinate is the form a pin and a lock entry both take")
    void coordinateIsIdAtVersion() {
        assertThat(backend(Set.of(Capability.of("rest-api"))).coordinate()).isEqualTo("backend-spring-java@1.4.0");
    }

    private static Recipe backend(Set<Capability> provides) {
        return new Recipe(
                RecipeId.of("backend-spring-java"),
                RecipeVersion.parse("1.4.0"),
                "3.5.5",
                RecipeKind.BACKEND,
                "Spring Boot (Java)",
                provides,
                Set.of(Capability.of("build-tool")),
                Set.of(RecipeId.of("backend-spring-kotlin")),
                List.of(),
                Set.of("groupId", "packageName"),
                List.of(FileRule.always("files/**")),
                List.of(),
                false);
    }
}
