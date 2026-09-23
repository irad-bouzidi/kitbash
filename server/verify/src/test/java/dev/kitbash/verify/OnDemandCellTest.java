package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code kitbash-37}'s half of the runner: a cell built from a selection nobody enumerated.
 *
 * <p>Two properties are worth asserting and neither is obvious from reading the code. The first is
 * that a requested combination is built by the <em>same commands</em> the nightly would use — a
 * green answer to "does my combination build?" has to mean what a green cell means, and it would
 * not if the two derivations drifted. The second is that the deadline the API promises is real
 * rather than per step.
 */
class OnDemandCellTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    @DisplayName("steps")
    class Steps {

        @Test
        @DisplayName("a requested full-stack selection runs exactly what the matrix runs")
        void matchesTheMatrix() {
            Map<String, Object> options = Map.of(
                    "buildTool", "build-gradle-kts",
                    "backend", "backend-spring-java",
                    "frontend", "frontend-react-vite",
                    "ci", "ci-gitlab",
                    "typedClient", true);

            List<Cell.Step> steps = CellSteps.forOptions(options);

            assertThat(steps).extracting(Cell.Step::ecosystem).containsExactly("jvm", "node", "ci");
            assertThat(steps.get(0).commands())
                    .containsExactly("./gradlew build --no-daemon", "./gradlew generateApiClient --no-daemon");
            assertThat(steps.get(1).commands()).contains("pnpm typecheck", "pnpm build");
            assertThat(CellSteps.sharedWorkspace(options))
                    .as("the JVM build writes the client the frontend build reads")
                    .isTrue();
        }

        @Test
        @DisplayName("Maven's typed client is one lifecycle, not a build followed by a task")
        void mavenGeneratesInsideVerify() {
            List<Cell.Step> steps =
                    CellSteps.forOptions(Map.of("buildTool", "build-maven", "backend", "b", "typedClient", true));

            assertThat(steps.get(0).commands()).containsExactly("./mvnw -B -Pclient verify");
        }

        @Test
        @DisplayName("a selection with no CI provider is not failed for the pipeline it declined")
        void noProviderNoLint() {
            assertThat(CellSteps.forOptions(Map.of("backend", "b", "buildTool", "build-gradle-kts")))
                    .extracting(Cell.Step::ecosystem)
                    .containsExactly("jvm");
        }
    }

    @Nested
    @DisplayName("deadline")
    class Deadline {

        private final CellRunner runner =
                new CellRunner(Repository.locate(), new Containers(Containers.DEFAULT_IMAGES, "2", "4g", 900));

        @Test
        @DisplayName("a step gets what is left of the run's budget, never more")
        void cutsTheStepTimeoutToWhatIsLeft() {
            Containers bounded = runner.within(Instant.now().plus(Duration.ofSeconds(30)));

            assertThat(bounded).isNotNull();
            assertThat(bounded.timeoutSeconds())
                    .as("thirty seconds left is a thirty-second step, not a fifteen-minute one")
                    .isLessThanOrEqualTo(30);
        }

        @Test
        @DisplayName("a generous deadline leaves the per-step timeout alone")
        void keepsThePerStepCeiling() {
            assertThat(runner.within(Instant.now().plus(Duration.ofHours(1))).timeoutSeconds())
                    .isEqualTo(900);
        }

        @Test
        @DisplayName("a spent budget starts no step at all")
        void refusesToStartAStepThatCannotFinish() {
            assertThat(runner.within(Instant.now().minusSeconds(1)))
                    .as("a step started with nothing left would report its own timeout, which reads "
                            + "as a slow build rather than as a run that ran out")
                    .isNull();
        }

        @Test
        @DisplayName("no deadline is the matrix's case, and changes nothing")
        void noDeadlineKeepsTheDefaults() {
            assertThat(runner.within(null).timeoutSeconds()).isEqualTo(900);
        }
    }

    @Nested
    @DisplayName("selection")
    class RequestedSelection {

        @Test
        @DisplayName("the requested selection is written where generate.sh will look for it")
        void writesTheSelection() throws Exception {
            Repository repository = Repository.locate();
            String id = "test-" + java.util.UUID.randomUUID();
            var envelope = JSON.readTree(
                    """
                    {"schemaVersion":1,"projectName":"asked-for",
                     "options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts","ci":"ci-github"},
                     "variables":{"groupId":"com.example"}}
                    """);

            Cell cell = RequestedCell.from(repository, id, envelope);

            assertThat(repository.selectionFor(cell)).exists();
            assertThat(JSON.readTree(repository.selectionFor(cell).toFile())).isEqualTo(envelope);
            assertThat(cell.triggers())
                    .as("a requested cell belongs to no schedule; giving it one would put a "
                            + "user's selection into the nightly")
                    .isEmpty();
            assertThat(cell.steps()).extracting(Cell.Step::ecosystem).containsExactly("jvm", "ci");

            java.nio.file.Files.deleteIfExists(repository.selectionFor(cell));
        }
    }
}
