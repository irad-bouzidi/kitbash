package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        @DisplayName("the §17 combinations are all covered, and so is the second build tool")
        void coversTheCombinations() {
            // backend only, frontend only, both, and both with Docker declined (§17). The
            // frontend-only case is the one most likely to break silently, which is why it is a
            // cell rather than an assumption.
            //
            // backend-maven is §28's: a second build tool is only proven by building with it, and
            // an assertion that the other four still pass says nothing about the fifth.
            assertThat(CellLoader.load(Repository.locate().cells()))
                    .extracting(Cell::id)
                    .containsExactlyInAnyOrder(
                            "backend-only", "backend-maven", "frontend-only", "full-stack", "full-stack-no-docker");
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
            assertThatThrownBy(() -> new Cell("empty", null, "s.json", Set.of("nightly"), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("verifies nothing");
        }
    }

    @Nested
    @DisplayName("the docker command")
    class ContainerCommand {

        private final Containers containers = new Containers(Containers.DEFAULT_IMAGES, "2", "4g", 900);

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
