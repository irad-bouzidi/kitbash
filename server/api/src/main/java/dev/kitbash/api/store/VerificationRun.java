package dev.kitbash.api.store;

import java.time.Instant;
import java.util.UUID;

/**
 * A verification of one selection against one catalog (§10).
 *
 * <p>{@code requestedBy} is null for the nightly matrix, which nobody requested. That null is the
 * difference between "somebody is waiting for this" and "this is routine", and {@code kitbash-37}
 * reads it to decide whether a result needs to be served back to anyone.
 */
public record VerificationRun(
        UUID id,
        UUID requestedBy,
        String selection,
        String lock,
        String selectionHash,
        String catalogDigest,
        VerificationStatus status,
        String logKey,
        Instant startedAt,
        Instant finishedAt,
        Instant expiresAt) {}
