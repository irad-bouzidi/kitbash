package dev.kitbash.api.contributed;

import dev.kitbash.api.security.Caller;
import dev.kitbash.api.store.ContributedRecipe;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/contributed-recipes} — submit, review, approve, revoke (§47).
 *
 * <p>The lifecycle §47's "done when" names, as four endpoints. Verification is not one of them:
 * it is driven by the existing {@code /api/v1/verify} machinery and reported back here, because a
 * second verification path for contributed recipes would be a second thing to keep honest.
 *
 * <p>Who may do what is in {@code SecurityConfiguration} rather than here, except the one rule
 * that cannot be expressed as a path: <b>a reviewer may not approve their own submission.</b> That
 * depends on two rows and a caller, so it lives in this class where all three are in scope.
 */
@RestController
@RequestMapping("/api/v1/contributed-recipes")
@Profile("persistence")
public class ContributedRecipeController {

    private final ContributedRecipeService service;

    public ContributedRecipeController(ContributedRecipeService service) {
        this.service = service;
    }

    /**
     * @param manifest {@code recipe.yaml} as text
     * @param files the {@code files/} tree, path to content
     */
    public record SubmissionRequest(String manifest, Map<String, String> files) {}

    /**
     * What a submission looks like from outside.
     *
     * <p>The content is deliberately absent. A listing that carried every submitted template would
     * be a listing nobody paginates and a response nobody reads; the review page fetches one
     * submission's content when a reviewer opens it.
     */
    public record SubmissionResponse(
            UUID id,
            String recipeId,
            String version,
            String status,
            String contentHash,
            UUID submittedBy,
            Instant submittedAt,
            UUID reviewedBy,
            Instant reviewedAt,
            String revokedReason) {

        static SubmissionResponse of(ContributedRecipe recipe) {
            return new SubmissionResponse(
                    recipe.id(),
                    recipe.recipeId(),
                    recipe.version(),
                    recipe.status().wireName(),
                    recipe.contentHash(),
                    recipe.submittedBy(),
                    recipe.submittedAt(),
                    recipe.reviewedBy(),
                    recipe.reviewedAt(),
                    recipe.revokedReason());
        }
    }

    /** @param reason why it was withdrawn — carried into every flag, so it is not optional */
    public record RevocationRequest(String reason) {}

    /** @param generationsFlagged how many projects contain code from the withdrawn recipe */
    public record RevocationResponse(String recipeId, int generationsFlagged) {}

    @PostMapping
    public ResponseEntity<SubmissionResponse> submit(@RequestBody SubmissionRequest request) {
        ContributedRecipe submitted = service.submit(
                request.manifest(),
                request.files() == null ? Map.of() : request.files(),
                Caller.ownerId().orElseThrow());
        return ResponseEntity.status(HttpStatus.CREATED).body(SubmissionResponse.of(submitted));
    }

    @org.springframework.web.bind.annotation.GetMapping
    public List<SubmissionResponse> queue() {
        return service.awaitingReview().stream().map(SubmissionResponse::of).toList();
    }

    @PostMapping("/{id}/approve")
    public SubmissionResponse approve(@PathVariable UUID id) {
        return SubmissionResponse.of(service.approve(id, Caller.ownerId().orElseThrow()));
    }

    @PostMapping("/{id}/revoke")
    public RevocationResponse revoke(@PathVariable UUID id, @RequestBody RevocationRequest request) {
        int flagged = service.revoke(id, Caller.ownerId().orElseThrow(), request.reason());
        return new RevocationResponse(id.toString(), flagged);
    }
}
