package dev.kitbash.api.preset;

import dev.kitbash.api.generate.GenerateRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A preset as the wizard reads it back.
 *
 * <p>{@code mine} rather than an owner id: §18 has no user directory, so there is nobody to name.
 * What the page actually needs to know is whether the Edit and Delete buttons should be there.
 *
 * @param revision which revision this is; an edit writes a new one rather than overwriting
 * @param staleReason why this preset cannot be generated from, or null when it can
 */
public record PresetResponse(
        UUID id,
        String name,
        String description,
        String visibility,
        GenerateRequest selection,
        String versionPolicy,
        Map<String, String> pinnedRecipes,
        int revision,
        boolean mine,
        List<String> recipeIds,
        String staleReason,
        Instant createdAt,
        Instant updatedAt) {}
