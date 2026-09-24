package dev.kitbash.api.store;

import java.time.Instant;
import java.util.UUID;

/**
 * One submission, as a row (§47, ADR 0004).
 *
 * <p>Immutable except for its review state. A recipe's content never changes in place because a
 * generation's lock names a version and a digest, and both have to keep meaning what they meant
 * when the zip was taken — a revised recipe is a new row at a new version, which is the same rule
 * §10 applies to presets and for the same reason.
 *
 * @param manifest {@code recipe.yaml} exactly as submitted
 * @param content the {@code files/} tree as a JSON path-to-content map, stored rather than
 *     referenced: the thing a reviewer approved has to be the thing that renders, and an external
 *     blob store would put a second system between the two
 * @param contentHash sha256 over the same sorted {@code (path, bytes)} walk {@code CatalogLoader}
 *     uses for a git recipe, so a contributed recipe's identity does not depend on which half of
 *     the catalog it lives in
 * @param verifiedByRun the verification run that gated approval — §47 requires the matrix to pass
 *     before approval, and this is what makes that checkable afterwards rather than asserted
 * @param reviewedBy the named reviewer §47 asks for. Null before review, not because approval is
 *     optional but because a row exists before it happens.
 */
public record ContributedRecipe(
        UUID id,
        String recipeId,
        String namespace,
        String version,
        String manifest,
        String content,
        String contentHash,
        ContributedRecipeStatus status,
        UUID submittedBy,
        Instant submittedAt,
        UUID reviewedBy,
        Instant reviewedAt,
        UUID verifiedByRun,
        UUID revokedBy,
        Instant revokedAt,
        String revokedReason) {}
