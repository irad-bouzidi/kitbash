package dev.kitbash.api.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §10's line, where it is drawn: <i>only hashes, recipe ids and selections cross into the runner —
 * never project or package names.</i>
 *
 * <p>The reason this is a test rather than a comment is the failure mode. A name that leaks does
 * not break anything: the cell builds, the matrix is green, and a customer's package name sits in
 * a CI log on somebody else's retention schedule until the day somebody looks. Nothing goes red.
 */
class HistoryCellsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A stored generation with something identifying in every field that could carry one. */
    private static final String STORED =
            """
            {
              "schemaVersion": 1,
              "projectName": "zarquon-ledger",
              "options": {"backend": "backend-spring-java", "buildTool": "build-gradle-kts", "docker": true},
              "variables": {
                "groupId": "com.zarquon",
                "packageName": "com.zarquon.ledger",
                "javaVersion": "21",
                "entityName": "Invoice",
                "entityTable": "invoices",
                "envPrefix": "ZARQUON"
              }
            }
            """;

    /** Everything in the fixture that identifies whoever generated it. */
    private static final String[] NAMES = {
        "zarquon-ledger", "com.zarquon", "com.zarquon.ledger", "Invoice", "invoices", "ZARQUON", "zarquon"
    };

    private static Map<String, Object> anonymised() throws Exception {
        return HistoryCells.anonymise(JSON.readTree(STORED));
    }

    @Nested
    @DisplayName("what does not cross")
    class Stripped {

        @Test
        @DisplayName("no name from the stored selection appears anywhere in the cell")
        void noNamesReachTheRunner() throws Exception {
            String rendered = JSON.writeValueAsString(anonymised());

            // The whole serialised envelope, not field by field: a field-by-field assertion checks
            // the fields somebody thought of, and this checks the bytes that actually leave.
            for (String name : NAMES) {
                assertThat(rendered)
                        .as("'%s' identifies whoever generated this and has no business in a cell", name)
                        .doesNotContain(name);
            }
        }

        @Test
        @DisplayName("a field added to the stored shape tomorrow does not arrive here by default")
        void buildsByConstructionRatherThanByRemoval() throws Exception {
            String stored = STORED.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"ownerEmail\": \"a@b.c\",");

            Map<String, Object> cell = HistoryCells.anonymise(JSON.readTree(stored));

            // Removal would have needed somebody to add `ownerEmail` to a deny-list. Construction
            // reads the two keys it wants, so the new field is absent without anyone deciding.
            assertThat(cell).doesNotContainKey("ownerEmail");
            assertThat(JSON.writeValueAsString(cell)).doesNotContain("a@b.c");
        }

        @Test
        @DisplayName("every variable is replaced, not filtered")
        void variablesAreReplacedWholesale() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, String> variables = (Map<String, String>) anonymised().get("variables");

            // A deny-list of name-like keys is a list somebody has to extend the next time a recipe
            // declares a variable, and forgetting is silent.
            assertThat(variables).containsEntry("packageName", "com.example.matrix");
            assertThat(variables).containsEntry("entityName", "Widget");
            assertThat(variables.values())
                    .noneMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains("zarquon"));
        }
    }

    @Nested
    @DisplayName("what does cross")
    class Kept {

        @Test
        @DisplayName("the options survive exactly, because they are what the cell is about")
        void optionsAreUntouched() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map<String, Object>) anonymised().get("options");

            assertThat(options)
                    .containsEntry("backend", "backend-spring-java")
                    .containsEntry("buildTool", "build-gradle-kts")
                    .containsEntry("docker", true);
        }

        @Test
        @DisplayName("a flag stays a boolean, so the cell resolves the way the generation did")
        void flagsKeepTheirType() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map<String, Object>) anonymised().get("options");

            // "true" as a string resolves differently from true, and the cell would then verify a
            // combination nobody generated.
            assertThat(options.get("docker")).isInstanceOf(Boolean.class);
        }

        @Test
        @DisplayName("the envelope is generatable: a schema version and a name it can be given")
        void isAValidEnvelope() throws Exception {
            Map<String, Object> cell = anonymised();

            assertThat(cell).containsEntry("schemaVersion", 1).containsEntry("projectName", "history-cell");
        }
    }
}
