package dev.kitbash.api.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.api.error.ResourceNotFoundException;
import dev.kitbash.api.generate.GenerateRequest;
import dev.kitbash.api.store.Generation;
import dev.kitbash.api.store.GenerationRepository;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Generations as receipts (§3, §7, §10, §24).
 *
 * <p>The two replay modes are not a convenience pair. <b>Exact</b> answers <i>what did I ship in
 * March?</i> and <b>current</b> answers <i>what would that same choice give me today?</i> — and
 * conflating them would mean somebody reproducing a shipped artifact quietly getting this week's
 * framework instead.
 *
 * <p>Exact is possible at all because §4 made generation deterministic: the same selection against
 * the same catalog is the same bytes, so replaying a lock that still resolves reproduces the zip
 * rather than approximating it. When it cannot, it says so by name — a silent re-resolution
 * wearing the word "exact" is the failure this mode exists to prevent.
 */
@Service
@Profile("persistence")
public class GenerationService {

    private static final int HISTORY_PAGE = 50;

    private final GenerationRepository generations;
    private final Catalog catalog;
    private final ObjectMapper json;

    public GenerationService(GenerationRepository generations, Catalog catalog, ObjectMapper json) {
        this.generations = generations;
        this.catalog = catalog;
        this.json = json;
    }

    public List<GenerationResponse> historyFor(UUID owner) {
        return generations.historyFor(owner, HISTORY_PAGE).stream()
                .map(this::describe)
                .toList();
    }

    public GenerationResponse read(UUID id, UUID owner) {
        return describe(requireOwned(id, owner));
    }

    /** Keeps a row and its artifact out of the sweep, which §10 expresses as clearing the expiry. */
    public GenerationResponse keep(UUID id, UUID owner) {
        requireOwned(id, owner);
        generations.keep(id);
        return read(id, owner);
    }

    /**
     * The selection to feed back through the pipeline, and what it will mean.
     *
     * <p>Both modes hand back the same envelope — the one stored on the row — because the
     * difference between them is not <i>what is generated</i> but <i>against which catalog</i>.
     * Exact refuses when the catalog has moved; current proceeds and reports the move.
     */
    public Replay replay(UUID id, UUID owner, ReplayMode mode) {
        Generation generation = requireOwned(id, owner);
        Map<String, String> locked = lockOf(generation);

        LockDiff drift = LockDiff.between(locked, currentVersionsOf(locked.keySet()));

        if (mode == ReplayMode.EXACT) {
            requireLockStillResolves(locked);
        }
        return new Replay(selectionOf(generation), mode, drift, generation.catalogDigest(), catalog.digest());
    }

    /** A generation's selection, for a download that re-renders it. */
    public GenerateRequest selectionFor(UUID id, UUID owner) {
        return selectionOf(requireOwned(id, owner));
    }

    /** The §24 debugging tool: two receipts, one diff. */
    public LockDiff diff(UUID first, UUID second, UUID owner) {
        return LockDiff.between(lockOf(requireOwned(first, owner)), lockOf(requireOwned(second, owner)));
    }

    // --- replay rules ------------------------------------------------------

    /**
     * Exact replay, refused by name.
     *
     * <p>§24 asks for this to fail loudly and usefully, and "usefully" is the operative word: the
     * message carries {@code recipe@version}, because the person reading it has to decide whether
     * to check out an older catalog or accept a current replay, and neither decision can be made
     * from "this is no longer available".
     */
    private void requireLockStillResolves(Map<String, String> locked) {
        List<String> gone = new ArrayList<>();
        locked.forEach((recipeId, version) -> {
            Optional<Recipe> recipe = catalog.find(RecipeId.of(recipeId));
            if (recipe.isEmpty() || !recipe.get().version().toString().equals(version)) {
                gone.add(recipeId + "@" + version);
            }
        });

        if (!gone.isEmpty()) {
            throw GenerationError.invalidIdentifier(
                            "mode",
                            "'exact'",
                            "cannot reproduce this generation: the catalog no longer has " + String.join(", ", gone),
                            "Check out the catalog at digest this generation recorded, or replay it with "
                                    + "mode=current — which re-resolves against today's versions and tells you "
                                    + "exactly what moved.")
                    .asException();
        }
    }

    private Map<String, String> currentVersionsOf(java.util.Set<String> recipeIds) {
        Map<String, String> current = new TreeMap<>();
        recipeIds.forEach(recipeId -> catalog.find(RecipeId.of(recipeId))
                .ifPresent(recipe -> current.put(recipeId, recipe.version().toString())));
        return current;
    }

    // --- plumbing ----------------------------------------------------------

    private Generation requireOwned(UUID id, UUID owner) {
        Generation generation = generations.findById(id).orElseThrow(GenerationService::noSuchGeneration);
        if (generation.ownerId() == null || !generation.ownerId().equals(owner)) {
            // A history row is personal: "not yours" and "not there" are the same answer, or the
            // endpoint becomes a way to count somebody else's generations.
            throw noSuchGeneration();
        }
        return generation;
    }

    private GenerationResponse describe(Generation generation) {
        Map<String, String> locked = lockOf(generation);
        return new GenerationResponse(
                generation.id(),
                generation.projectName(),
                selectionOf(generation),
                locked,
                generation.catalogDigest(),
                generation.selectionHash(),
                generation.status().wireName(),
                generation.durationMillis(),
                generation.sizeBytes(),
                generation.kept(),
                // Whether an exact replay is still possible, answered now rather than when
                // somebody clicks: a button that might fail is worse than one that says why.
                LockDiff.between(locked, currentVersionsOf(locked.keySet())).isEmpty(),
                generation.createdAt(),
                generation.expiresAt(),
                generation.pushedProjectUrl());
    }

    private Map<String, String> lockOf(Generation generation) {
        try {
            return new TreeMap<>(json.readValue(generation.lock(), new TypeReference<Map<String, String>>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Generation " + generation.id() + " holds a lock that will not parse", e);
        }
    }

    private GenerateRequest selectionOf(Generation generation) {
        try {
            return json.readValue(generation.selection(), GenerateRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Generation " + generation.id() + " holds a selection that will not parse", e);
        }
    }

    /** Which question is being asked (§24). */
    public enum ReplayMode {
        /** What did I ship in March? Refuses rather than substituting today's versions. */
        EXACT,
        /** What would that same choice give me today? */
        CURRENT;

        public static ReplayMode of(String wireName) {
            return wireName == null || wireName.isBlank()
                    ? EXACT
                    : switch (wireName.toLowerCase(java.util.Locale.ROOT)) {
                        case "exact" -> EXACT;
                        case "current" -> CURRENT;
                        default ->
                            throw GenerationError.invalidIdentifier(
                                            "mode",
                                            "'" + wireName + "'",
                                            "must be 'exact' or 'current'",
                                            "'exact' asks what you shipped; 'current' asks what the same choice gives "
                                                    + "you today.")
                                    .asException();
                    };
        }

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What a replay resolved to, and what moved since the original run. */
    public record Replay(
            GenerateRequest selection,
            ReplayMode mode,
            LockDiff drift,
            String originalCatalogDigest,
            String currentCatalogDigest) {}

    /**
     * One sentence for a generation nobody can find, wherever it is missed from.
     *
     * <p>Two call sites, one wording: "not found" and "not yours" are the same answer on purpose,
     * because telling a caller which of the two it was tells them whether somebody else's id is
     * real.
     */
    private static ResourceNotFoundException noSuchGeneration() {
        return new ResourceNotFoundException(
                "generation",
                "No generation with that id.",
                "Your own generations are listed at /api/v1/generations; history is kept for thirty "
                        + "days, so an older one is gone rather than hidden.");
    }
}
