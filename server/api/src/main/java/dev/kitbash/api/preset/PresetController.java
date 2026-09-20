package dev.kitbash.api.preset;

import dev.kitbash.api.generate.GenerateController;
import dev.kitbash.api.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Presets — "save as template", named correctly (§3) and versioned correctly (§7).
 *
 * <p>The list this serves is the landing page for a returning user. §9 folds the Implementation
 * Plan's Dashboard into it deliberately: a page of counter tiles earns nothing, and what somebody
 * coming back actually wants is their own stacks, one click from a cold load — which is what
 * {@code POST /{id}/generate} is for.
 *
 * <p>Only present when there is a database. The generator half of this service runs without one
 * (§10, §12), and an endpoint that would 500 on every call is worse than one that is honestly not
 * there.
 */
@RestController
@RequestMapping("/api/v1/presets")
@Profile("persistence")
public class PresetController {

    private final PresetService presets;
    private final GenerateController generate;

    public PresetController(PresetService presets, GenerateController generate) {
        this.presets = presets;
        this.generate = generate;
    }

    /** Everything this caller may see: their own, plus what others have shared. */
    @GetMapping
    public List<PresetResponse> list() {
        return presets.visibleTo(owner());
    }

    @GetMapping("/{id}")
    public PresetResponse read(@PathVariable UUID id) {
        return presets.read(id, owner());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PresetResponse create(@RequestBody PresetRequest request, Authentication caller) {
        return presets.create(request, caller);
    }

    /** An edit writes a new revision, so the definition somebody linked to still resolves. */
    @PutMapping("/{id}")
    public PresetResponse update(@PathVariable UUID id, @RequestBody PresetRequest request, Authentication caller) {
        return presets.update(id, request, caller);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        presets.delete(id, owner());
    }

    /**
     * The one click (§9).
     *
     * <p>Resolution happens now rather than when the preset was saved, which is what "tracks
     * latest" means mechanically — the stored selection is fed through the same pipeline a wizard
     * request goes through, including the rate limiter and the cache, because it is the same
     * request with the envelope read from a row instead of a body.
     */
    @PostMapping(value = "/{id}/generate", produces = "application/zip")
    public void generate(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        generate.generate(presets.selectionOf(id, owner()), request, response);
    }

    private static UUID owner() {
        return Caller.ownerId()
                .orElseThrow(() -> new IllegalStateException(
                        "An authenticated endpoint was reached without a subject, which cannot happen."));
    }
}
