package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.core.recipe.Catalog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §40: the nightly builds the cross-product <em>and</em> the stacks people actually use.
 *
 * <p>What is worth testing is which popular stacks become cells, because both exclusions are easy
 * to get wrong in a way that looks fine. Deduplicating by the wrong key rebuilds everything the
 * enumeration already covers and the nightly quietly doubles; treating a removed recipe as a
 * failure makes every catalog change look like a regression.
 */
class HistoryCellsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Catalog CATALOG = ReferenceProjects.loadCatalog().catalog();

    private static final Repository REPOSITORY = Repository.locate();

    private static final List<Cell> ENUMERATED = Enumeration.cells(CATALOG, REPOSITORY);

    private static HistoryCells.HistoryCell cell(String hash, int generations, String selectionJson) {
        try {
            return new HistoryCells.HistoryCell(hash, generations, JSON.readTree(selectionJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A stack the cross-product never builds.
     *
     * <p>The six axes are enumerated in full; the four feature toggles ride on bits of the cell
     * index, so each enumerated cell has <em>one</em> feature set. All four on, over a combination
     * whose index says otherwise, is exactly the case §12 says history exists to catch.
     */
    private static String onlyInUsage() {
        return """
            {"schemaVersion":1,"projectName":"history-cell",
             "options":{"buildTool":"build-gradle-kts","backend":"backend-spring-java",
                        "architecture":"layered","database":"db-postgres-flyway",
                        "frontend":"frontend-react-vite","ci":"ci-github","docker":true,
                        "auth":true,"observability":true,"typedClient":true,"tracing":true},
             "variables":{"groupId":"com.example","packageName":"com.example.matrix","javaVersion":"21",
                          "entityName":"Widget","entityTable":"widgets","envPrefix":"MATRIX"}}
            """;
    }

    /** The options of a cell the enumeration definitely produces, with §40's neutral names. */
    private static String enumeratedShape() {
        return """
            {"schemaVersion":1,"projectName":"history-cell",
             "options":{"buildTool":"build-gradle-kts","backend":"backend-spring-java",
                        "architecture":"layered","database":"db-postgres-flyway","ci":"ci-github",
                        "docker":true},
             "variables":{"groupId":"com.example","packageName":"com.example.matrix","javaVersion":"21",
                          "entityName":"Widget","entityTable":"widgets","envPrefix":"MATRIX"}}
            """;
    }

    @Nested
    @DisplayName("what becomes a cell")
    class Selection {

        @Test
        @DisplayName("a stack the cross-product already covers is not built twice")
        void deduplicatesAgainstTheEnumeration() {
            HistoryCells.Result result = HistoryCells.select(
                    REPOSITORY, CATALOG, ENUMERATED, List.of(cell("sha256:aaa", 40, enumeratedShape())));

            // The interesting history cells are the few the cross-product misses; the top hashes
            // are mostly stacks it already builds, and running them again is container-minutes for
            // an answer the nightly already has.
            assertThat(result.cells()).isEmpty();
        }

        @Test
        @DisplayName("deduplication is by shape, because a history cell's hash can never match")
        void deduplicatesByShapeNotHash() {
            // §10 replaces the names before a selection leaves the API, so a history cell's hash
            // differs from the generation it came from *and* from any enumerated cell. Comparing
            // hashes would deduplicate nothing at all — and the symptom would be a nightly that
            // silently doubled rather than anything going red.
            String sameShapeOtherHash = enumeratedShape().replace("sha256:aaa", "sha256:zzz");

            assertThat(HistoryCells.select(
                                    REPOSITORY,
                                    CATALOG,
                                    ENUMERATED,
                                    List.of(cell("sha256:zzz", 12, sameShapeOtherHash)))
                            .cells())
                    .isEmpty();
        }

        @Test
        @DisplayName("a stack only real usage has is built, and labelled so a failure can be weighed")
        void keepsACombinationTheCrossProductMisses() {
            // The six axes are enumerated in full; the four feature toggles ride on bits of the
            // cell index, so each enumerated cell has *one* feature set. A team that turns all four
            // on over a combination whose index says otherwise has a stack the cross-product never
            // builds — which is exactly the case §12 says history exists to catch.
            List<Cell> cells = HistoryCells.select(
                            REPOSITORY, CATALOG, ENUMERATED, List.of(cell("sha256:beefcafe1234", 37, onlyInUsage())))
                    .cells();

            assertThat(cells).hasSize(1);
            assertThat(cells.getFirst().id())
                    .as("the id begins h- so the status page can label it, and carries the hash so a "
                            + "reader can find the stack it came from")
                    .startsWith("h-")
                    .contains("beefcafe");
        }
    }

    @Nested
    @DisplayName("a catalog that has moved on")
    class RemovedRecipes {

        @Test
        @DisplayName("a stack naming a recipe that no longer exists is skipped with a note")
        void skipsRatherThanFails() {
            String gone = enumeratedShape().replace("backend-spring-java", "backend-spring-groovy");

            HistoryCells.Result result =
                    HistoryCells.select(REPOSITORY, CATALOG, ENUMERATED, List.of(cell("sha256:old", 9, gone)));

            assertThat(result.cells())
                    .as("a removed recipe is a catalog change, not a regression — reporting it as a "
                            + "failure would make every deletion look like a broken nightly")
                    .isEmpty();
            assertThat(result.notes())
                    .singleElement()
                    .asString()
                    .contains("backend-spring-groovy")
                    .contains("9 generations")
                    .contains("catalog change");
        }

        @Test
        @DisplayName("the skip is reported, because a set that shrinks silently drifts")
        void theSkipIsNotSilent() {
            String gone = enumeratedShape().replace("ci-github", "ci-jenkins");

            assertThat(HistoryCells.select(REPOSITORY, CATALOG, ENUMERATED, List.of(cell("sha256:old", 3, gone)))
                            .notes())
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("when there is no history")
    class NoHistory {

        @Test
        @DisplayName("no API to ask means the enumerated matrix and a note saying so")
        void isNotAFailure() {
            // A nightly that fell over because a database was down would be a nightly that stops
            // reporting the catalog is broken, which is the one thing it exists to do.
            HistoryCells.Result result = HistoryCells.fetch(REPOSITORY, CATALOG, ENUMERATED);

            assertThat(result.cells()).isEmpty();
            assertThat(result.notes()).singleElement().asString().contains("KITBASH_HISTORY_URL");
        }
    }

    @Nested
    @DisplayName("the weekly record")
    class Published {

        @Test
        @DisplayName("every stack that was offered is recorded, ran or skipped")
        void recordsTheWholeSet() throws Exception {
            String gone = enumeratedShape().replace("backend-spring-java", "backend-spring-groovy");
            List<HistoryCells.HistoryCell> offered =
                    List.of(cell("sha256:beefcafe1234", 37, onlyInUsage()), cell("sha256:deadbeef5678", 9, gone));
            HistoryCells.Result result = HistoryCells.select(REPOSITORY, CATALOG, ENUMERATED, offered);

            HistoryCells.publish(REPOSITORY, result.cells(), result.considered());

            String written = java.nio.file.Files.readString(REPOSITORY.output().resolve("history-cells.tsv"));

            // Both, because §40's report is about the *set*: a stack that stops being offered is
            // the drift worth noticing, and one that is offered but skipped is a coverage hole.
            assertThat(written).contains("h-beefcafe1234").contains("\tran");
            assertThat(written).contains("h-deadbeef5678").contains("\tskipped");
            assertThat(written).contains("\t37\t").contains("\t9\t");
        }

        @Test
        @DisplayName("sorted, so a diff between two nights is a diff rather than a reordering")
        void isSorted() throws Exception {
            HistoryCells.publish(
                    REPOSITORY,
                    List.of(),
                    List.of(cell("sha256:ccc111", 1, onlyInUsage()), cell("sha256:aaa222", 2, onlyInUsage())));

            List<String> lines =
                    java.nio.file.Files.readAllLines(REPOSITORY.output().resolve("history-cells.tsv"));

            assertThat(lines).isSortedAccordingTo(String::compareTo);
        }
    }

    @Nested
    @DisplayName("the report")
    class Report {

        @Test
        @DisplayName("history cells are labelled on the status page, with what could not be added")
        void labelsHistoryCells() {
            CellResult.Matrix matrix = new CellResult.Matrix(
                    "nightly",
                    "sha256:abc",
                    List.of(
                            CellResult.passed(
                                    "e-gradle-java-layered-github", java.time.Duration.ofSeconds(30), null, "x"),
                            CellResult.failed(
                                    "h-beefcafe1234", java.time.Duration.ofSeconds(40), null, "pnpm build", "y")),
                    java.time.Duration.ofSeconds(70));

            String html = StatusPage.html(matrix, List.of("h-old (9 generations) names backend-spring-groovy"));

            assertThat(html)
                    .as("§40 wants history-sourced cells distinguishable, so it is obvious which "
                            + "failures affect real users")
                    .contains("from history");
            assertThat(html.indexOf("from history"))
                    .as("the label belongs to the history cell, not to the enumerated one")
                    .isGreaterThan(html.indexOf("e-gradle-java-layered-github"));
            assertThat(html).contains("backend-spring-groovy").contains("History cells");
        }
    }
}
