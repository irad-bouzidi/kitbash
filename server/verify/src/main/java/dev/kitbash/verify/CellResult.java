package dev.kitbash.verify;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * What happened to one cell.
 *
 * <p>Duration is recorded from the first run rather than when it starts to hurt: §12 gives phase 3
 * a twenty-minute nightly budget, and that is only defensible with a timing history to argue from.
 *
 * <p>Logs go to a file per cell from the start, because {@code kitbash-37} has to serve exactly
 * those bytes back to a user who asked to verify their own combination.
 */
public record CellResult(
        String cellId, Outcome outcome, Duration duration, Path log, String failedStep, String reproduce) {

    public enum Outcome {
        PASSED,
        FAILED,
        /** The cell never ran: generation itself was refused, so there was nothing to build. */
        NOT_GENERATED
    }

    public boolean passed() {
        return outcome == Outcome.PASSED;
    }

    public static CellResult passed(String cellId, Duration duration, Path log, String reproduce) {
        return new CellResult(cellId, Outcome.PASSED, duration, log, null, reproduce);
    }

    public static CellResult failed(String cellId, Duration duration, Path log, String failedStep, String reproduce) {
        return new CellResult(cellId, Outcome.FAILED, duration, log, failedStep, reproduce);
    }

    public static CellResult notGenerated(String cellId, Duration duration, Path log, String reproduce) {
        return new CellResult(cellId, Outcome.NOT_GENERATED, duration, log, "generate", reproduce);
    }

    /** The whole matrix: what ran, what failed, and how long it all took. */
    public record Matrix(String trigger, String catalogDigest, List<CellResult> results, Duration duration) {

        public Matrix {
            results = List.copyOf(results);
        }

        public boolean green() {
            return results.stream().allMatch(CellResult::passed);
        }

        public long failures() {
            return results.stream().filter(result -> !result.passed()).count();
        }
    }
}
