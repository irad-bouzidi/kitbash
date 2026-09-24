package dev.kitbash.api.store;

import java.time.Instant;
import java.util.UUID;

/**
 * One generation that happened (§10).
 *
 * <p>{@code lock} is the column that matters. It is what makes a row replayable long after its
 * zip has expired, and it is small enough that keeping it forever costs nothing — which is why
 * §10 can say "nothing reproducible is lost at expiry" and mean it.
 *
 * <p>{@code projectName} is kept so a user recognises their own rows and is deleted with the
 * record. It must never reach a log or a metric.
 */
public record Generation(
        UUID id,
        UUID ownerId,
        UUID presetId,
        String projectName,
        String selection,
        String lock,
        String catalogDigest,
        String selectionHash,
        String artifactKey,
        GenerationStatus status,
        Integer durationMillis,
        Integer sizeBytes,
        Instant createdAt,
        Instant expiresAt,
        boolean kept,
        /**
         * Where this generation was pushed, when it was pushed anywhere (§46).
         *
         * <p>Nullable, and most rows will keep it null: §18 makes the zip the default and §46 says
         * the push must not degrade it. A column rather than a table, because a push is not a
         * separate event — it is how one generation was taken delivery of.
         */
        String pushedProjectUrl) {}
