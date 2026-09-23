package dev.kitbash.api.history;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/history/cells} — the stacks the nightly should also build (§10, §12, §40).
 *
 * <h2>Why the runner asks rather than queries</h2>
 *
 * <p>§12 keeps verification independent of the API, its auth and its persistence, so that a red
 * cell means the generator is broken rather than the deployment. That rule is about <em>running</em>
 * a cell: generation goes through the CLI and no cell touches a database.
 *
 * <p>Choosing which cells to run is a different question, and history is the only honest answer to
 * it — §12 calls it <i>the piece neither plan had</i>. So the runner asks for a list, once, before
 * it starts; and when nobody answers it runs the enumerated matrix and says so in the report. A
 * nightly that fell over because a database was down would be a nightly that stops telling anyone
 * the catalog is broken.
 *
 * <p>Everything §10 keeps out of the runner is stripped by {@link HistoryCells} before it reaches
 * here, and there is a test that asserts nothing identifying survives.
 */
@RestController
@RequestMapping("/api/v1/history")
@Profile("persistence")
public class HistoryCellController {

    /**
     * §40's twenty.
     *
     * <p>A cap rather than a parameter with no ceiling: this list becomes container-minutes in the
     * nightly, and an unbounded one is a caller deciding how long the nightly takes.
     */
    private static final int MOST = 50;

    private final HistoryCells cells;

    public HistoryCellController(HistoryCells cells) {
        this.cells = cells;
    }

    @GetMapping("/cells")
    public ResponseEntity<List<HistoryCellResponse>> cells(@RequestParam(defaultValue = "20") int limit) {
        List<HistoryCellResponse> body = cells.top(Math.clamp(limit, 1, MOST)).stream()
                .map(HistoryCellResponse::of)
                .toList();

        return ResponseEntity.ok()
                // Never cached. The list is read once a night and a stale one would quietly run
                // last month's stacks.
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * One stack, as the runner receives it.
     *
     * @param selectionHash the hash of the original generation — a label and a count key, not the
     *     hash of the selection below, which has different names by construction
     * @param generations how often it has been built, so a red cell can be weighed against how many
     *     people it affects
     */
    public record HistoryCellResponse(String selectionHash, int generations, JsonNode selection) {

        static HistoryCellResponse of(HistoryCells.HistoryCell cell) {
            return new HistoryCellResponse(cell.selectionHash(), cell.generations(), HistoryCells.toJson(cell));
        }
    }
}
