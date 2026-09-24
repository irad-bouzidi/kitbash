package dev.kitbash.api.history;

import dev.kitbash.api.generate.GenerateRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One generation, as the history page reads it (§10, §24).
 *
 * @param lock recipe id to the exact version that produced this — the valuable part (§7)
 * @param exactlyReproducible whether the catalog still holds every version in that lock, answered
 *     when the row is read rather than when somebody clicks Replay
 * @param expiresAt when the sweep will take it, or null once kept
 */
public record GenerationResponse(
        UUID id,
        String projectName,
        GenerateRequest selection,
        Map<String, String> lock,
        String catalogDigest,
        String selectionHash,
        String status,
        Integer durationMillis,
        Integer sizeBytes,
        boolean kept,
        boolean exactlyReproducible,
        Instant createdAt,
        Instant expiresAt,
        /**
         * Where this generation was pushed, when it was (§46).
         *
         * <p>Null for the usual case — the zip is the default and stays it. When it is set, it is
         * the thing a user is looking for: they came back to history to find where the project
         * went, and the URL is the answer.
         */
        String pushedProjectUrl) {}
