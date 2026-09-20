package dev.kitbash.core.plan;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.RecipeId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The output of stage 3: every file the selected recipes will contribute, in recipe order, plus the
 * patches that will be applied once those files exist (§6).
 *
 * <p>Ordering is the plan's job, not the renderer's. Entries arrive in resolved recipe order — base
 * first, CI last, ties by recipe id — so the later stages can be order-agnostic and the output zip
 * stays byte-identical across runs (§4).
 *
 * <p>A later entry for a path that an earlier recipe already claimed wins, and the plan remembers
 * both owners so an override can be reported rather than discovered. Overrides are legitimate — an
 * architecture variant replacing a default source file is exactly that — but a silent one is how
 * two recipes end up fighting over a file nobody realised they shared.
 */
public record FilePlan(List<FileEntry> entries, List<PatchOp> patches, Map<String, List<RecipeId>> claims) {

    public FilePlan {
        entries = List.copyOf(entries);
        patches = List.copyOf(patches);
        claims = Map.copyOf(claims);
    }

    public static FilePlan empty() {
        return new FilePlan(List.of(), List.of(), Map.of());
    }

    /**
     * Collapses the entries to one per path, last writer winning, preserving first-claim order so
     * the result is still deterministic.
     */
    public List<FileEntry> effectiveEntries() {
        Map<String, FileEntry> byPath = new LinkedHashMap<>();
        for (FileEntry entry : entries) {
            byPath.put(entry.path(), entry);
        }
        return List.copyOf(byPath.values());
    }

    public int fileCount() {
        return effectiveEntries().size();
    }

    public long declaredBytes() {
        return effectiveEntries().stream().mapToLong(FileEntry::declaredSize).sum();
    }

    /** Whether any selected recipe produces this path — what {@code PATCH_TARGET_MISSING} asks. */
    public boolean produces(String path) {
        return claims.containsKey(path);
    }

    public Optional<RecipeId> ownerOf(String path) {
        List<RecipeId> owners = claims.get(path);
        return owners == null || owners.isEmpty() ? Optional.empty() : Optional.of(owners.get(owners.size() - 1));
    }
}
