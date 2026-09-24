package dev.kitbash.api.store;

import java.time.Instant;
import java.util.UUID;

/**
 * A generation that used a recipe somebody later withdrew (§47).
 *
 * <p>§47 asks revocation to "pull a recipe and flag the generations that used it", and this is the
 * flag. A row rather than a column on {@code generation}, because one generation can be caught by
 * two revocations and a column would let the second overwrite the first — and somebody holding a
 * project built from two withdrawn recipes needs to know about both.
 *
 * <p>Written when the revocation happens rather than computed when somebody looks. The lock is
 * {@code jsonb} and the containment query is cheap, so this is not about speed: a flag that exists
 * only while a page is open is a flag no notification, export or audit can ever be built on.
 */
public record GenerationFlag(UUID generationId, String recipeId, String reason, Instant flaggedAt) {}
