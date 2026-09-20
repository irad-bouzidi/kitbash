package dev.kitbash.api.history;

import dev.kitbash.api.generate.GenerateController;
import dev.kitbash.api.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generation history: receipts, and what to do with one (§3, §7, §24).
 *
 * <p>Every row carries a lock, which is why a download can re-render rather than fetch: the render
 * is deterministic (§4), so bytes that expired can be made again from a row that is small enough to
 * keep forever. The object store that would serve them directly is {@code kitbash-27}.
 */
@RestController
@RequestMapping("/api/v1/generations")
@Profile("persistence")
public class GenerationController {

    private final GenerationService history;
    private final GenerateController generate;

    public GenerationController(GenerationService history, GenerateController generate) {
        this.history = history;
        this.generate = generate;
    }

    @GetMapping
    public List<GenerationResponse> list() {
        return history.historyFor(owner());
    }

    @GetMapping("/{id}")
    public GenerationResponse read(@PathVariable UUID id) {
        return history.read(id, owner());
    }

    /**
     * The zip again.
     *
     * <p>Re-rendered from the stored selection until {@code kitbash-27} brings the object store —
     * which is exactly as correct and merely slower, because the same selection against the same
     * catalog is the same bytes.
     */
    @PostMapping(value = "/{id}/download", produces = "application/zip")
    public void download(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        generate.generate(history.selectionFor(id, owner()), request, response);
    }

    /**
     * Replays a generation, in one of the two modes §24 keeps apart.
     *
     * <p>{@code exact} refuses when the catalog has moved, naming what is missing; {@code current}
     * proceeds and reports what moved. Either way the response says which ran — a reproduction
     * that quietly used different versions would be worse than none.
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<ReplayResponse> replay(
            @PathVariable UUID id, @RequestParam(value = "mode", required = false) String mode) {
        GenerationService.Replay replay = history.replay(id, owner(), GenerationService.ReplayMode.of(mode));

        List<LockDiff.VersionChange> changes = new ArrayList<>(replay.drift().changed());
        changes.addAll(replay.drift().added());
        changes.addAll(replay.drift().removed());

        return ResponseEntity.ok()
                // The digest that answers "which catalog rendered this?" without opening a log.
                .header("X-Kitbash-Catalog-Digest", replay.currentCatalogDigest())
                .body(new ReplayResponse(
                        replay.mode().wireName(),
                        replay.originalCatalogDigest(),
                        replay.currentCatalogDigest(),
                        !replay.drift().isEmpty(),
                        replay.drift().summary(),
                        List.copyOf(changes)));
    }

    /** Keeps a row and its artifact out of the 30-day sweep (§10). */
    @PostMapping("/{id}/keep")
    public GenerationResponse keep(@PathVariable UUID id) {
        return history.keep(id, owner());
    }

    /**
     * Two receipts, one diff — §7's stated debugging tool.
     *
     * <p>When somebody says <i>this used to work</i>, the difference between the run that worked
     * and the run that did not is usually one line of this.
     */
    @GetMapping("/{id}/diff/{otherId}")
    public LockDiff diff(@PathVariable UUID id, @PathVariable UUID otherId) {
        return history.diff(id, otherId, owner());
    }

    private static UUID owner() {
        return Caller.ownerId()
                .orElseThrow(() -> new IllegalStateException(
                        "An authenticated endpoint was reached without a subject, which cannot happen."));
    }
}
