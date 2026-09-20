package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.selection.SelectionEnvelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The hostile corpus, pointed at the catalog that actually ships (§13).
 *
 * <p>{@code InputValidationTest} in {@code core} proves the rules reject what they are given. This
 * proves the rules are <i>reached</i> — that a request arriving with a crafted variable is refused
 * by the real catalog, the real parser and the real recipes, rather than by a fixture that happens
 * to be stricter than production.
 *
 * <p>The gap it closes is a specific one. Every variable in {@code recipes/_catalog.yaml} declares
 * a {@code pattern}, and until {@code kitbash-20} only the wizard enforced it — which makes it a
 * suggestion, because a crafted request never goes near the wizard. {@code entityName} becomes a
 * Java class name, a SQL table and a URL path.
 */
class HostileSelectionTest {

    private static GenerationPipeline pipeline;

    @BeforeAll
    static void loadTheRealCatalog() {
        CatalogLoader.LoadedCatalog loaded = ReferenceProjects.loadCatalog();
        pipeline = ReferenceProjects.pipeline(loaded);
    }

    /** The selection the reference project uses, with one variable replaced. */
    private static SelectionEnvelope withEntityName(String entityName) {
        return new SelectionEnvelope(
                1,
                "demo",
                Map.of(
                        "buildTool", "build-gradle-kts",
                        "backend", "backend-spring-java",
                        "database", "db-postgres-flyway"),
                Map.of(
                        "groupId", "com.example",
                        "packageName", "com.example.demo",
                        "javaVersion", "21",
                        "entityName", entityName,
                        "entityTable", "widgets",
                        "envPrefix", "DEMO"));
    }

    @ParameterizedTest(name = "entityName = {0}")
    @ValueSource(
            strings = {
                "Widget; DROP TABLE widgets",
                "Widget extends Object implements Runnable",
                "Widget$(whoami)",
                "Widget`id`",
                "../../etc/passwd",
                "widget", // lowercase: it becomes a class name, which PascalCase is not optional for
                "Widget Two",
                "Wîdget",
                "<script>alert(1)</script>"
            })
    @DisplayName("a crafted entityName is refused by the catalog's own declared pattern")
    void refusesCraftedVariables(String entityName) {
        assertThatThrownBy(() -> pipeline.parse(withEntityName(entityName)))
                .isInstanceOf(GenerationException.class)
                .extracting(thrown -> ((GenerationException) thrown).error().code())
                .isEqualTo(ErrorCode.INVALID_IDENTIFIER);
    }

    @Test
    @DisplayName("the rejection quotes the catalog's pattern, so the rule and its source agree")
    void namesTheDeclaredRule() {
        assertThatThrownBy(() -> pipeline.parse(withEntityName("widget")))
                .hasMessageContaining("entityName")
                .hasMessageContaining("^[A-Z][A-Za-z0-9]*$");
    }

    @Test
    @DisplayName("the entity names real projects use keep working")
    void acceptsRealEntityNames() {
        List.of("Widget", "Order", "CustomerAccount", "Invoice2")
                .forEach(name -> assertThatCode(() -> pipeline.parse(withEntityName(name)))
                        .as("%s", name)
                        .doesNotThrowAnyException());
    }

    /**
     * The wizard enforces the catalog's pattern client-side and the server enforces its own rule
     * for the same field. If those two ever disagree, one of them is lying to the user: either the
     * wizard accepts what the API refuses, or it refuses what the API would have taken.
     */
    @Test
    @DisplayName("the catalog's declared projectName pattern agrees with the server's own rule")
    void theDeclaredRuleAndTheServerRuleAgree() {
        java.util.regex.Pattern declared = ReferenceProjects.loadCatalog().catalog().variables().stream()
                .filter(variable -> variable.id().equals("projectName"))
                .findFirst()
                .map(variable -> java.util.regex.Pattern.compile(variable.pattern()))
                .orElseThrow();

        for (String acceptable : dev.kitbash.core.hostile.HostileInputs.acceptableProjectNames()) {
            assertThat(declared.matcher(acceptable).matches())
                    .as("the catalog rejects '%s', which the server accepts", acceptable)
                    .isTrue();
        }
        for (dev.kitbash.core.hostile.HostileInputs.Case hostile :
                dev.kitbash.core.hostile.HostileInputs.projectNames()) {
            assertThat(declared.matcher(hostile.value()).matches())
                    .as("the catalog accepts %s, which the server rejects", hostile)
                    .isFalse();
        }
    }

    /**
     * The end of the argument that matters: a value that <i>is</i> legal still only ever becomes
     * text in a file. Nothing in the generator runs a process, so there is no shell for a value to
     * reach — see {@code GeneratorExecutesNothingTest}, which asserts that structurally.
     */
    @Test
    @DisplayName("an accepted value lands as text, not as something that runs")
    void valuesAreOnlyEverText() {
        GeneratedProject generated = pipeline.generate(withEntityName("Widget"));

        assertThat(generated.workspace().files()).isNotEmpty();
        assertThat(generated.workspace().files().keySet())
                .allSatisfy(path -> assertThat(path).doesNotContain("..").doesNotStartWith("/"));
    }
}
