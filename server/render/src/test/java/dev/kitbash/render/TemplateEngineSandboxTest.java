package dev.kitbash.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The sandbox, tested adversarially rather than described.
 *
 * <p>§13 starts from the premise that a generator writes strings into files somebody will then
 * execute, so a template that can reach the JVM or the filesystem is a remote code execution
 * waiting for a recipe review to be sloppy. Each case below is a route somebody would actually try.
 */
class TemplateEngineSandboxTest {

    private static String render(String source) {
        TemplateEngine engine = TemplateEngine.over(TemplateRegistry.of(Map.of("t", source)));
        return engine.render(
                "t",
                "base",
                TemplateVariables.builder()
                        .put("projectName", "customer-management")
                        .put("packageName", "com.acme.customer")
                        .build());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{{ ''.getClass() }}",
                "{{ projectName.getClass().getName() }}",
                "{{ projectName.class }}",
                "{{ projectName.toUpperCase() }}",
                "{{ ''.getClass().forName('java.lang.Runtime') }}",
            })
    @DisplayName("no route from a value to the JVM survives")
    void refusesMethodAccess(String source) {
        // Pebble ships a blacklist validator; a blacklist is a list of the attacks somebody already
        // thought of. There is nothing in the variable map worth calling a method on, so all method
        // access is denied instead.
        assertThatThrownBy(() -> render(source))
                .isInstanceOf(GenerationException.class)
                .satisfies(thrown -> assertThat(
                                ((GenerationException) thrown).error().code())
                        .isEqualTo(ErrorCode.RENDER_FAILED));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{% include \"/etc/passwd\" %}",
                "{% include \"../../../../etc/passwd\" %}",
                "{% extends \"/etc/passwd\" %}",
                "{% import \"/etc/passwd\" %}",
            })
    @DisplayName("there is no filesystem behind the loader, so an include has nothing to traverse")
    void refusesFilesystemIncludes(String source) {
        assertThatThrownBy(() -> render(source))
                .isInstanceOf(GenerationException.class)
                .satisfies(thrown -> assertThat(
                                ((GenerationException) thrown).error().code())
                        .isEqualTo(ErrorCode.RENDER_FAILED));
    }

    @Test
    @DisplayName("an undefined variable is an error, not an empty string")
    void refusesUndefinedVariables() {
        // The alternative is a silently empty package declaration, discovered by the user at their
        // first compile rather than by us at render time.
        assertThatThrownBy(() -> render("package {{ projetName }};"))
                .isInstanceOf(GenerationException.class)
                .satisfies(thrown -> {
                    var error = ((GenerationException) thrown).error();
                    assertThat(error.code()).isEqualTo(ErrorCode.RENDER_FAILED);
                    assertThat(error.recipe()).isEqualTo("base");
                    assertThat(error.file()).isEqualTo("t");
                });
    }

    @Test
    @DisplayName("a failure names the line, so the fix is one jump away")
    void failuresNameTheLine() {
        assertThatThrownBy(() -> render("line one\nline two\n{{ missing }}\n"))
                .isInstanceOf(GenerationException.class)
                .satisfies(thrown -> assertThat(((dev.kitbash.core.error.GenerationError.RenderFailed)
                                        ((GenerationException) thrown).error())
                                .line())
                        .isEqualTo(3));
    }

    @Test
    @DisplayName("an include of a template the catalog does contain works, because that is the point")
    void allowsRegisteredIncludes() {
        TemplateEngine engine = TemplateEngine.over(TemplateRegistry.of(Map.of(
                "readme", "# {{ projectName }}\n{% include \"badge\" %}",
                "badge", "built with kitbash")));

        String out = engine.render(
                "readme",
                "base",
                TemplateVariables.builder().put("projectName", "svc").build());

        assertThat(out).isEqualTo("# svc\nbuilt with kitbash");
    }

    @Test
    @DisplayName("output is not HTML-escaped, because generated Java is not HTML")
    void doesNotEscape() {
        assertThat(render("if (a < b && c > d) { }")).isEqualTo("if (a < b && c > d) { }");
    }

    @Test
    @DisplayName("the variable map refuses anything that is not a validated primitive")
    void variableMapHoldsPrimitivesOnly() {
        assertThatThrownBy(() ->
                        TemplateVariables.builder().put("catalog", new Object()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validated primitives only");

        assertThatThrownBy(() -> TemplateVariables.builder()
                        .put("things", List.of(new Object()))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(TemplateVariables.builder()
                        .put("name", "svc")
                        .put("docker", true)
                        .put("port", 8080)
                        .put("features", List.of("auth"))
                        .build()
                        .size())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("an edited template is never served from the cache, because the key carries its hash")
    void cacheIsContentAddressed() {
        assertThat(TemplateEngine.over(TemplateRegistry.of(Map.of("t", "one {{ projectName }}")))
                        .render("t", "base", vars()))
                .isEqualTo("one svc");
        assertThat(TemplateEngine.over(TemplateRegistry.of(Map.of("t", "two {{ projectName }}")))
                        .render("t", "base", vars()))
                .isEqualTo("two svc");
    }

    private static TemplateVariables vars() {
        return TemplateVariables.builder().put("projectName", "svc").build();
    }
}
