package dev.kitbash.api.store;

import java.time.Instant;
import java.util.UUID;

/**
 * A selection somebody named and saved (§10).
 *
 * <p>{@code selection} and {@code pinnedRecipes} are JSON held as text. §10 stores them as
 * {@code jsonb} with only the named columns promoted, and promoting more "because we might query
 * them" re-creates the catalog-in-the-database problem one column at a time.
 *
 * <p>Revisions rather than updates in place: a preset is a thing people link to, and silently
 * changing what a link resolves to is worse than an extra row.
 */
public record Preset(
        UUID id,
        UUID ownerId,
        String name,
        String description,
        Visibility visibility,
        String selection,
        VersionPolicy versionPolicy,
        String pinnedRecipes,
        int revision,
        Instant createdAt,
        Instant updatedAt) {}
