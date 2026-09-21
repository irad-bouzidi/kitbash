package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The full matrix, checked for the properties that make it worth running. */
class EnumerationTest {

    private static final List<Cell> CELLS =
            Enumeration.cells(ReferenceProjects.loadCatalog().catalog(), Repository.locate());

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode optionsOf(Cell cell) {
        try {
            return JSON.readTree(Files.readAllBytes(Repository.locate().selectionFor(cell)))
                    .path("options");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    @DisplayName("size")
    class Size {

        /**
         * §35 asks for this assertion by name, and for the reason it gives: the enumeration reads
         * the catalog, so a recipe declared into one slot too many turns ninety-eight cells into
         * nine hundred — and the symptom would be a nightly that quietly stops finishing rather
         * than anything going red.
         */
        @Test
        @DisplayName("the matrix is the size it is meant to be, so growth is a decision")
        void isTheExpectedSize() {
            assertThat(CELLS)
                    .as("the enumeration changed size. If that was intended, change "
                            + "Enumeration.EXPECTED_CELLS in the same commit and say why in the message; "
                            + "if it was not, a recipe has been declared into a slot it does not belong in.")
                    .hasSize(Enumeration.EXPECTED_CELLS);
        }

        @Test
        @DisplayName("every cell has a distinct, readable id")
        void idsAreUniqueAndSpeak() {
            Set<String> ids = CELLS.stream().map(Cell::id).collect(Collectors.toSet());

            assertThat(ids).hasSize(CELLS.size());
            // A failure names the cell and nothing else, so the name has to carry the combination.
            assertThat(ids).allSatisfy(id -> assertThat(id).contains("-").doesNotContain(" "));
        }
    }

    @Nested
    @DisplayName("coverage")
    class Coverage {

        @Test
        @DisplayName("every value of every enumerated axis appears")
        void coversTheAxes() {
            assertThat(values("buildTool")).contains("build-gradle-kts", "build-maven");
            assertThat(values("backend")).contains("backend-spring-java", "backend-spring-kotlin");
            assertThat(values("architecture")).contains("layered", "hexagonal", "modular-monolith");
            assertThat(values("ci")).contains("ci-gitlab", "ci-github");
            assertThat(values("docker")).contains("true", "false");
            assertThat(values("frontend")).contains("frontend-react-vite");
        }

        /**
         * The claim the feature bits make. Each toggle is on in half the cells, so every pair of
         * toggles occurs at both settings of the other — pairwise coverage by construction rather
         * than by a sampling rule somebody has to trust.
         */
        @Test
        @DisplayName("each feature is on in some cells and off in others, and pairs both ways")
        void coversTheFeaturePairs() {
            for (String feature : List.of("auth", "observability", "typedClient", "tracing")) {
                assertThat(values(feature))
                        .as("%s is never switched, so the matrix proves nothing about it", feature)
                        .contains("true", "false");
            }

            assertThat(CELLS).anySatisfy(cell -> {
                JsonNode options = optionsOf(cell);
                assertThat(options.path("auth").asBoolean()).isTrue();
                assertThat(options.path("observability").asBoolean()).isTrue();
            });
            assertThat(CELLS).anySatisfy(cell -> {
                JsonNode options = optionsOf(cell);
                assertThat(options.path("auth").asBoolean()).isTrue();
                assertThat(options.path("observability").asBoolean()).isFalse();
            });
        }

        @Test
        @DisplayName("nothing unreachable is enumerated")
        void enumeratesOnlyValidSelections() {
            for (Cell cell : CELLS) {
                JsonNode options = optionsOf(cell);
                if (options.hasNonNull("backend")) {
                    assertThat(options.path("database").asText())
                            .as("%s has a backend and no database, which every backend requires", cell.id())
                            .isNotEmpty();
                }
                if (options.path("typedClient").asBoolean()) {
                    assertThat(options.hasNonNull("frontend"))
                            .as("%s asks for a typed client with nothing to put it in", cell.id())
                            .isTrue();
                }
                if (options.path("tracing").asBoolean()) {
                    assertThat(options.path("observability").asBoolean())
                            .as("%s traces without the recipe that configures tracing", cell.id())
                            .isTrue();
                }
            }
        }

        /** §18's exemption, spent once and visible here. */
        @Test
        @DisplayName("frontend-only is sampled rather than enumerated")
        void samplesTheStandaloneFrontend() {
            List<Cell> standalone = CELLS.stream()
                    .filter(cell -> !optionsOf(cell).hasNonNull("backend"))
                    .toList();

            assertThat(standalone)
                    .as("§18 grants one sampling exemption and this is it; enumerating this axis "
                            + "would double the matrix for one conditional")
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("sharding")
    class Sharding {

        @Test
        @DisplayName("the shards partition the matrix exactly, with no cell in two of them")
        void partitionsTheMatrix() {
            List<String> everything = CELLS.stream().map(Cell::id).sorted().toList();

            List<String> shards = new java.util.ArrayList<>();
            for (int index = 1; index <= 6; index++) {
                MatrixMain.shardOf(CELLS, index + "/6").forEach(cell -> shards.add(cell.id()));
            }

            assertThat(shards.stream().sorted().toList()).isEqualTo(everything);
            assertThat(Set.copyOf(shards)).hasSize(everything.size());
        }

        @Test
        @DisplayName("a shard holds the same cells every time, so a flaky one can be rerun")
        void isDeterministic() {
            assertThat(MatrixMain.shardOf(CELLS, "3/6")).isEqualTo(MatrixMain.shardOf(CELLS, "3/6"));
        }

        @Test
        @DisplayName("the shards are within one cell of each other in size")
        void isBalanced() {
            List<Integer> sizes = java.util.stream.IntStream.rangeClosed(1, 6)
                    .map(index -> MatrixMain.shardOf(CELLS, index + "/6").size())
                    .boxed()
                    .toList();

            assertThat(java.util.Collections.max(sizes) - java.util.Collections.min(sizes))
                    .as("shard sizes %s", sizes)
                    .isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("a nonsense shard is refused rather than silently running everything")
        void refusesNonsense() {
            assertThatThrownBy(() -> MatrixMain.shardOf(CELLS, "7/6")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MatrixMain.shardOf(CELLS, "2")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static Set<String> values(String option) {
        return CELLS.stream()
                .map(EnumerationTest::optionsOf)
                .map(options -> options.path(option))
                .filter(value -> !value.isMissingNode())
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
    }
}
