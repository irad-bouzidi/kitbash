package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Everything about the matrix that does not need a Docker daemon.
 *
 * <p>The container run itself is exercised by the job that runs it, not here: a unit test that
 * builds a real project for three minutes is a unit test nobody runs. What these cover is the part
 * that decides *what* gets run and *what is reported* — the two places a bug would make the matrix
 * green for the wrong reason.
 */
class CellTest {

    @Nested
    @DisplayName("cells as data")
    class Definitions {

        @Test
        @DisplayName("the repository's own cells load, and each names a selection that exists")
        void repositoryCellsAreValid() {
            Repository repository = Repository.locate();

            List<Cell> cells = CellLoader.load(repository.cells());

            assertThat(cells).isNotEmpty();
            assertThat(cells).allSatisfy(cell -> {
                assertThat(cell.id()).isNotBlank();
                assertThat(cell.steps()).isNotEmpty();
                // A cell pointing at a selection that is not there fails three minutes into a
                // container run; here it fails in milliseconds.
                assertThat(repository.selectionFor(cell)).exists();
            });
        }

        @Test
        @DisplayName("every cell runs on both triggers today, and every step names a known ecosystem")
        void everyCellIsWiredUp() {
            Repository repository = Repository.locate();
            Containers containers = Containers.standard();

            for (Cell cell : CellLoader.load(repository.cells())) {
                assertThat(cell.triggers()).as("triggers of %s", cell.id()).contains("merge-request", "nightly");
                for (Cell.Step step : cell.steps()) {
                    assertThat(containers.images())
                            .as("image for %s in %s", step.ecosystem(), cell.id())
                            .containsKey(step.ecosystem());
                }
            }
        }

        @Test
        @DisplayName("the §17 combinations are covered, and so is each axis the catalog has grown")
        void coversTheCombinations() {
            // backend only, frontend only, both, and both with Docker declined (§17). The
            // frontend-only case is the one most likely to break silently, which is why it is a
            // cell rather than an assumption.
            //
            // The other seven are the three axes the catalog has grown — build tool (§28),
            // language (§29) and architecture (§30) — covered pairwise. Pairwise rather than
            // exhaustively: 2 x 2 x 3 is twelve combinations and eight cells already contain
            // every pair drawn from any two axes, which is where the defects that matter live.
            // A fragment correct for Kotlin-on-Gradle and for Java-on-Maven and wrong for their
            // combination is caught; a three-way interaction with no two-way symptom is not,
            // and that is the trade being made.
            //
            // Auth (§31) and observability (§32) are not further axes here. Both add the same
            // files whichever architecture, language or build tool is chosen — the whole point of
            // them landing in `config/` — so each is carried by reference projects plus the one
            // cell covering the combination no reference has: a browser half for auth, and
            // metrics without auth for observability.
            assertThat(CellLoader.load(Repository.locate().cells()))
                    .extracting(Cell::id)
                    .containsExactlyInAnyOrder(
                            "backend-only",
                            "backend-maven",
                            "backend-kotlin",
                            "backend-kotlin-maven",
                            "backend-hexagonal",
                            "backend-hexagonal-kotlin-maven",
                            "backend-modular",
                            "backend-modular-java-maven",
                            "backend-observability",
                            "ci-github",
                            "ci-gitlab",
                            "frontend-only",
                            "full-stack",
                            "full-stack-auth",
                            "full-stack-no-docker",
                            "full-stack-typed",
                            "typed-client-contract");
        }

        /**
         * The pairwise claim above, asserted rather than asserted-in-a-comment.
         *
         * <p>A list of eleven cell ids is not evidence of coverage; it is evidence that somebody
         * wrote eleven files. This reads the selections and checks that every pair of values drawn
         * from two different axes appears together in some cell — so deleting a cell to make the
         * matrix faster fails here with the pair it stopped covering.
         */
        @Test
        @DisplayName("every pair of values from two axes appears in some cell")
        void coversEveryPair() {
            Repository repository = Repository.locate();
            List<Map<String, String>> selections = CellLoader.load(repository.cells()).stream()
                    .map(cell -> axesOf(repository.selectionFor(cell)))
                    .filter(axes -> axes.containsKey("backend"))
                    .toList();

            List<String> axes = List.of("buildTool", "backend", "architecture");
            List<String> uncovered = new java.util.ArrayList<>();
            for (int first = 0; first < axes.size(); first++) {
                for (int second = first + 1; second < axes.size(); second++) {
                    for (String left : valuesOf(selections, axes.get(first))) {
                        for (String right : valuesOf(selections, axes.get(second))) {
                            int one = first;
                            int two = second;
                            boolean covered = selections.stream()
                                    .anyMatch(selection -> left.equals(selection.get(axes.get(one)))
                                            && right.equals(selection.get(axes.get(two))));
                            if (!covered) {
                                uncovered.add(left + " + " + right);
                            }
                        }
                    }
                }
            }

            assertThat(uncovered)
                    .as("these combinations are not built by any cell, so nothing would notice a "
                            + "recipe fragment that is wrong for exactly that pair")
                    .isEmpty();
        }

        private static Map<String, String> axesOf(java.nio.file.Path selection) {
            try {
                com.fasterxml.jackson.databind.JsonNode options = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(java.nio.file.Files.readAllBytes(selection))
                        .path("options");
                Map<String, String> axes = new java.util.LinkedHashMap<>();
                for (String axis : List.of("buildTool", "backend", "architecture")) {
                    if (options.hasNonNull(axis)) {
                        axes.put(axis, options.path(axis).asText());
                    }
                }
                return axes;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        private static java.util.Set<String> valuesOf(List<Map<String, String>> selections, String axis) {
            return selections.stream()
                    .map(selection -> selection.get(axis))
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        }

        @Test
        @DisplayName("one cell can be named directly, and an unknown name lists the real ones")
        void selectsOneByName() {
            Path cells = Repository.locate().cells();

            assertThat(CellLoader.byId(cells, "full-stack").id()).isEqualTo("full-stack");
            assertThatThrownBy(() -> CellLoader.byId(cells, "fulll-stack"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("full-stack");
        }

        @Test
        @DisplayName("the run's own count reads as English, because a person reads it")
        void countsGrammatically() {
            assertThat(MatrixMain.count(1)).isEqualTo("1 cell");
            assertThat(MatrixMain.count(4)).isEqualTo("4 cells");
        }

        @Test
        @DisplayName("a trigger selects its cells, in a stable order")
        void selectsByTrigger(@TempDir Path directory) throws IOException {
            write(directory.resolve("zulu.json"), cellJson("zulu", List.of("nightly")));
            write(directory.resolve("alpha.json"), cellJson("alpha", List.of("merge-request", "nightly")));

            assertThat(CellLoader.forTrigger(directory, "merge-request"))
                    .extracting(Cell::id)
                    .containsExactly("alpha");
            assertThat(CellLoader.forTrigger(directory, "nightly"))
                    .extracting(Cell::id)
                    .containsExactly("alpha", "zulu");
        }

        @Test
        @DisplayName("a cell that runs no commands is refused, because it would pass by doing nothing")
        void refusesAnEmptyCell() {
            assertThatThrownBy(() -> new Cell("empty", null, "s.json", Set.of("nightly"), false, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("verifies nothing");
        }
    }

    @Nested
    @DisplayName("the docker command")
    class ContainerCommand {

        private final Containers containers = new Containers(Containers.DEFAULT_IMAGES, "2", "4g", 900);

        /**
         * §33's typed client is produced by one ecosystem and consumed by the other, so its cell
         * asks for one working tree. The mount point differs per image and the volume does not —
         * get that backwards and the second step silently builds the original project, which is a
         * green cell proving nothing.
         */
        @Test
        @DisplayName("a shared workspace mounts one volume at each image's own workspace path")
        void mountsTheSharedVolumeWhereEachImageLooks() {
            Cell.Step jvm = new Cell.Step("jvm", ".", List.of("./gradlew build"));
            Cell.Step node = new Cell.Step("node", "frontend", List.of("pnpm build"));

            assertThat(containers.commandFor(jvm, Path.of("/tmp/p"), "shared"))
                    .containsSequence("-v", "shared:/workspace");
            assertThat(containers.commandFor(node, Path.of("/tmp/p"), "shared")).containsSequence("-v", "shared:/work");
        }

        @Test
        @DisplayName("without one, nothing is mounted and every step starts from the same clean copy")
        void mountsNothingByDefault() {
            Cell.Step jvm = new Cell.Step("jvm", ".", List.of("./gradlew build"));

            assertThat(containers.commandFor(jvm, Path.of("/tmp/p")))
                    .noneMatch(argument -> argument.startsWith("shared:"))
                    .containsSequence("-v", "/tmp/p:/input:ro");
        }

        @Test
        @DisplayName("carries every §13 limit, and mounts the project read-only")
        void isFencedIn() {
            List<String> command = containers.commandFor(
                    new Cell.Step("jvm", ".", List.of("./gradlew build")), Path.of("/tmp/project"));

            String line = String.join(" ", command);
            assertThat(line)
                    .contains("--cpus=2")
                    .contains("--memory=4g")
                    .contains("--memory-swap=4g")
                    .contains("--pids-limit=2048")
                    .contains("--security-opt=no-new-privileges")
                    .contains("/tmp/project:/input:ro")
                    .contains("kitbash/verify-jvm:latest");
            // A wedged build has to die: §12 caps a cell at fifteen minutes.
            assertThat(command.subList(0, 3)).containsExactly("timeout", "--signal=KILL", "900");
        }

        @Test
        @DisplayName("tells the image which directory to run in, so one cell shape covers both halves")
        void passesTheWorkingDirectory() {
            assertThat(String.join(
                            " ",
                            containers.commandFor(
                                    new Cell.Step("node", "frontend", List.of("pnpm build")), Path.of("/tmp/project"))))
                    .contains("KITBASH_WORKING_DIRECTORY=frontend")
                    .contains("kitbash/verify-node:latest");
        }

        /**
         * The regression this guards is the one that broke the first frontend cell: a container per
         * command meant {@code pnpm install} wrote {@code node_modules} into a workspace that was
         * thrown away before {@code pnpm lint} ran.
         */
        @Test
        @DisplayName("a step is one container, so what one command writes the next command can read")
        void runsAStepInOneContainer() {
            Cell.Step step = new Cell.Step("node", "frontend", List.of("pnpm install", "pnpm lint", "pnpm build"));

            List<String> command = containers.commandFor(step, Path.of("/tmp/project"));

            assertThat(command).filteredOn("run"::equals).hasSize(1);
            String script = command.getLast();
            assertThat(script).containsSubsequence("pnpm install", "pnpm lint", "pnpm build");
            // And it stops at the first failure, naming it, rather than running on to the end.
            assertThat(script.lines().filter(line -> line.contains(Containers.FAILED)))
                    .hasSize(3);
        }

        @Test
        @DisplayName("a command carrying a quote is echoed back whole, not cut in half")
        void quotesSafely() {
            String tricky = "sh -c 'echo hi'";

            assertThat(Containers.script(new Cell.Step("node", ".", List.of(tricky))))
                    .contains(tricky)
                    .contains("'sh -c '\\''echo hi'\\'''");
        }

        @Test
        @DisplayName("an ecosystem with no image is refused by name, not by NullPointerException")
        void refusesAnUnknownEcosystem() {
            assertThatThrownBy(() ->
                            containers.commandFor(new Cell.Step("ruby", ".", List.of("rake")), Path.of("/tmp/project")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("verification/images");
        }
    }

    @Nested
    @DisplayName("the status page")
    class Status {

        private final CellResult.Matrix matrix = new CellResult.Matrix(
                "nightly",
                "sha256:abc",
                List.of(
                        CellResult.passed(
                                "backend-only",
                                Duration.ofSeconds(96),
                                Path.of("backend-only.log"),
                                "./verification/run-cell.sh a"),
                        CellResult.failed(
                                "full-stack",
                                Duration.ofSeconds(310),
                                Path.of("full-stack.log"),
                                "pnpm test",
                                "./verification/run-cell.sh b")),
                Duration.ofSeconds(406));

        @Test
        @DisplayName("reports pass, fail and duration per cell, and how to reproduce a failure")
        void rendersTheRun() {
            String html = StatusPage.html(matrix);

            assertThat(html)
                    .contains("1 failing")
                    .contains("2 cells")
                    .contains("backend-only")
                    .contains("1m 36s")
                    .contains("5m 10s")
                    .contains("./verification/run-cell.sh b");
        }

        @Test
        @DisplayName("the JSON form is what kitbash-38's badges will read")
        void rendersMachineReadably() {
            String json = StatusPage.json(matrix);

            assertThat(json).contains("\"green\": false").contains("\"failedStep\": \"pnpm test\"");
            assertThat(json).contains("\"catalogDigest\": \"sha256:abc\"");
        }

        @Test
        @DisplayName("one cell is a cell, not 1 cells")
        void countsGrammatically() {
            assertThat(StatusPage.html(new CellResult.Matrix(
                            "nightly",
                            "sha256:abc",
                            List.of(CellResult.passed("one", Duration.ZERO, Path.of("one.log"), "x")),
                            Duration.ZERO)))
                    .contains("1 cell ·")
                    .doesNotContain("1 cells");
        }

        @Test
        @DisplayName("a green run says so, and a matrix is green only when every cell is")
        void greenMeansEveryCell() {
            assertThat(matrix.green()).isFalse();
            assertThat(matrix.failures()).isEqualTo(1);
            assertThat(new CellResult.Matrix(
                                    "nightly",
                                    "sha256:abc",
                                    List.of(CellResult.passed("one", Duration.ZERO, Path.of("one.log"), "x")),
                                    Duration.ZERO)
                            .green())
                    .isTrue();
        }
    }

    private static String cellJson(String id, List<String> triggers) {
        return """
                {
                  "id": "%s",
                  "selection": "selections/whatever.json",
                  "triggers": [%s],
                  "steps": [{ "ecosystem": "jvm", "workingDirectory": ".", "commands": ["true"] }]
                }"""
                .formatted(
                        id,
                        triggers.stream()
                                .map(t -> "\"" + t + "\"")
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""));
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
