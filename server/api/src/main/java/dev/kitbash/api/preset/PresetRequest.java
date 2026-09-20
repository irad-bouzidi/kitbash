package dev.kitbash.api.preset;

import dev.kitbash.api.generate.GenerateRequest;
import java.util.Map;

/**
 * A preset as a client sends it (§7, §10).
 *
 * <p>What is stored is the <b>selection</b>, never the resolution. That is what "tracks latest"
 * means mechanically: the recipes are resolved when the preset is generated from, so a preset
 * saved today against Spring Boot 3.5 produces 3.6 tomorrow without anybody editing it.
 *
 * <p>{@code pinnedRecipes} is the escape hatch §7 keeps for compliance cases, and it records only
 * what was pinned — anything absent resolves fresh. Mixed policies are normal.
 *
 * @param versionPolicy {@code track_latest} (the default) or {@code pinned}
 * @param pinnedRecipes recipe id to exact version; ignored unless the policy is {@code pinned}
 */
public record PresetRequest(
        String name,
        String description,
        String visibility,
        GenerateRequest selection,
        String versionPolicy,
        Map<String, String> pinnedRecipes) {}
