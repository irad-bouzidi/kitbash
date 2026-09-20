package dev.kitbash.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One runner, driven by three triggers with different inputs (§12).
 *
 * <p>The merge-request job, the nightly matrix and — from {@code kitbash-37} — a user's on-demand
 * verification are the same machine. The Build Plan's nightly matrix and the Implementation Plan's
 * per-generation build validation were the same thing described twice, so this is built once and
 * the triggers only choose which cells go in.
 *
 * <p>Cells run one at a time on purpose. Each one already gets two CPUs and four gigabytes, and
 * two in parallel on a two-core runner makes both slower and the timing history meaningless —
 * which is the history phase 3's twenty-minute budget has to be argued from.
 */
public final class MatrixRunner {

    private final CellRunner cellRunner;
    private final Consumer<String> progress;

    public MatrixRunner(CellRunner cellRunner, Consumer<String> progress) {
        this.cellRunner = cellRunner;
        this.progress = progress;
    }

    public CellResult.Matrix run(String trigger, String catalogDigest, List<Cell> cells) {
        Instant started = Instant.now();
        List<CellResult> results = new ArrayList<>();

        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            progress.accept("[%d/%d] %s".formatted(i + 1, cells.size(), cell.id()));
            CellResult result = cellRunner.run(cell);
            progress.accept("      %s in %ds  (%s)"
                    .formatted(
                            result.outcome(),
                            result.duration().toSeconds(),
                            result.log().getFileName()));
            results.add(result);
        }

        return new CellResult.Matrix(trigger, catalogDigest, results, Duration.between(started, Instant.now()));
    }
}
