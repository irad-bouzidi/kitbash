package dev.kitbash.core.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.selection.OptionValue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * §7 calls {@code when} "the surface most likely to grow accidental features". These tests are the
 * fence: every form the language accepts is enumerated here, and everything else is a parse error.
 */
class WhenExpressionTest {

    private static final WhenContext CONTEXT = WhenContext.of(
            Map.of(
                    "architecture", OptionValue.text("hexagonal"),
                    "docker", OptionValue.flag(true),
                    "typedClient", OptionValue.flag(false),
                    "features", OptionValue.multi(List.of("auth"))),
            Set.of(Capability.of("rest-api"), Capability.of("project-root")));

    @ParameterizedTest
    @CsvSource({
        "always, true",
        "architecture == 'hexagonal', true",
        "architecture == 'layered', false",
        "architecture != 'layered', true",
        "docker, true",
        "!docker, false",
        "typedClient, false",
        "!typedClient, true",
        "features, true",
        "capability('rest-api'), true",
        "capability('spa'), false",
        "!capability('spa'), true",
        "!capability('rest-api'), false",
        "docker && architecture == 'hexagonal', true",
        "docker && typedClient, false",
        "typedClient || docker, true",
        "typedClient || architecture == 'layered', false",
    })
    @DisplayName("every accepted form evaluates as documented")
    void evaluates(String expression, boolean expected) {
        assertThat(WhenExpression.parse(expression).evaluate(CONTEXT)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a missing option is false, never an error at evaluation time")
    void missingOptionIsFalse() {
        // The loader already proved every referenced option is declared, so a missing value here
        // means "not set", not "typo". Failing at render time for that would be a broken download.
        assertThat(WhenExpression.parse("mobile").evaluate(CONTEXT)).isFalse();
        assertThat(WhenExpression.parse("mobile == 'expo'").evaluate(CONTEXT)).isFalse();
    }

    @Test
    @DisplayName("the options and capabilities an expression reads are enumerable, which is what the loader checks")
    void reportsWhatItReads() {
        WhenExpression expression = WhenExpression.parse("docker && capability('rest-api')");

        assertThat(expression.referencedOptions()).containsExactly("docker");
        assertThat(expression.referencedCapabilities()).containsExactly("rest-api");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "architecture == hexagonal",
                "architecture ~= 'hex'",
                "docker and typedClient",
                "(docker)",
                "1 + 1",
                "System.exit(0)",
            })
    @DisplayName("anything outside the grammar is a parse error, not a best guess")
    void refusesEverythingElse(String expression) {
        assertThatThrownBy(() -> WhenExpression.parse(expression))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supported forms");
    }

    @Test
    @DisplayName("mixing && and || is refused rather than given a precedence nobody would recall")
    void refusesMixedOperators() {
        assertThatThrownBy(() -> WhenExpression.parse("docker && typedClient || features"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split it into two rules");
    }

    @Test
    @DisplayName("a blank condition means always, which is how manifests omit it")
    void blankMeansAlways() {
        assertThat(WhenExpression.parse(null)).isEqualTo(new WhenExpression.Always());
        assertThat(WhenExpression.parse("  ")).isEqualTo(new WhenExpression.Always());
    }
}
