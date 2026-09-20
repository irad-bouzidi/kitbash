package dev.kitbash.api.preset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.api.generate.GenerateRequest;
import dev.kitbash.api.security.Caller;
import dev.kitbash.api.security.SecurityProperties;
import dev.kitbash.api.store.Preset;
import dev.kitbash.api.store.PresetRepository;
import dev.kitbash.api.store.VersionPolicy;
import dev.kitbash.api.store.Visibility;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.selection.SelectionValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * What a preset means, as opposed to how it is stored (§3, §7, §9, §10).
 *
 * <p>Three decisions live here and nowhere else.
 *
 * <p><b>Presets track the latest catalog.</b> §7 reconciles the two source plans on this and the
 * reasoning is worth keeping in view: the common case is <i>give me our standard service again</i>,
 * and a preset frozen on Spring Boot 3.2 is a trap that goes off six months later. Reproducibility
 * is not lost by this, because it lives on the <i>generation</i>, which carries a lock
 * ({@code kitbash-24}). Pinning stays available for the compliance cases that need it.
 *
 * <p><b>An edit is a new revision.</b> A preset is a thing people link to and build on, so the
 * previous definition survives the edit that replaced it.
 *
 * <p><b>Staleness is reported at load, not at render.</b> A preset naming a recipe the catalog no
 * longer has is a preset somebody saved a year ago; telling them so when they open it is an answer,
 * and failing halfway through a render is a bug report.
 */
@Service
@Profile("persistence")
public class PresetService {

    private final PresetRepository presets;
    private final Catalog catalog;
    private final ObjectMapper json;
    private final SecurityProperties roles;

    public PresetService(PresetRepository presets, Catalog catalog, ObjectMapper json, SecurityProperties roles) {
        this.presets = presets;
        this.catalog = catalog;
        this.json = json;
        this.roles = roles;
    }

    /**
     * What this caller may see: their own, plus anything shared.
     *
     * <p>§18 has one team, so {@code team} and {@code public} amount to the same audience today.
     * They are kept apart because they will not always: {@code public} is the one a role gates, and
     * collapsing them now would mean inventing the distinction again later from rows that had lost
     * it.
     */
    public List<PresetResponse> visibleTo(UUID owner) {
        List<Preset> mine = presets.findByOwner(owner);
        List<Preset> shared = presets.findByVisibleToOthers(owner);

        List<PresetResponse> all = new ArrayList<>();
        latestRevisions(mine).forEach(preset -> all.add(describe(preset, owner)));
        latestRevisions(shared).forEach(preset -> all.add(describe(preset, owner)));
        return List.copyOf(all);
    }

    public PresetResponse read(UUID id, UUID caller) {
        return describe(requireVisible(id, caller), caller);
    }

    /** The stored selection, for generating from. */
    public GenerateRequest selectionOf(UUID id, UUID caller) {
        Preset preset = requireVisible(id, caller);
        requireUsable(preset);
        return selection(preset);
    }

    public PresetResponse create(PresetRequest request, Authentication caller) {
        UUID owner = ownerOf(caller);
        Instant now = Instant.now();

        Preset preset = new Preset(
                UUID.randomUUID(),
                owner,
                requireName(request.name()),
                request.description(),
                visibilityOf(request, caller),
                serialize(requireSelection(request)),
                policyOf(request),
                pinned(request),
                presets.nextRevision(owner, requireName(request.name())),
                now,
                now);

        return describe(presets.insert(preset), owner);
    }

    /**
     * An update is an insert.
     *
     * <p>The row keeps its name and gains a revision, so a link to what the preset used to be still
     * resolves to what it used to be. The unique constraint on {@code (owner_id, name, revision)} is
     * what makes the read-then-insert safe when two edits race.
     */
    public PresetResponse update(UUID id, PresetRequest request, Authentication caller) {
        UUID owner = ownerOf(caller);
        Preset existing = requireOwned(id, owner);
        Instant now = Instant.now();

        Preset next = new Preset(
                UUID.randomUUID(),
                owner,
                existing.name(),
                request.description(),
                visibilityOf(request, caller),
                serialize(requireSelection(request)),
                policyOf(request),
                pinned(request),
                presets.nextRevision(owner, existing.name()),
                existing.createdAt(),
                now);

        return describe(presets.insert(next), owner);
    }

    /** Deletes every revision of the preset this id belongs to. */
    public void delete(UUID id, UUID owner) {
        Preset preset = requireOwned(id, owner);
        presets.deleteByOwnerAndName(owner, preset.name());
    }

    // --- rules -------------------------------------------------------------

    /**
     * Publishing needs a role; saving one for yourself does not (§13).
     *
     * <p>Checked here rather than in the filter chain because visibility is a field in a body, and a
     * path pattern cannot see it.
     */
    private Visibility visibilityOf(PresetRequest request, Authentication caller) {
        Visibility visibility =
                request.visibility() == null || request.visibility().isBlank()
                        ? Visibility.PRIVATE
                        : parse(request.visibility(), Visibility::of, "visibility", "private, team or public");

        if (visibility == Visibility.PUBLIC && !hasRole(caller, roles.publisherAuthority())) {
            throw GenerationError.invalidIdentifier(
                            "visibility",
                            "'public'",
                            "needs the " + roles.publisher() + " role",
                            "Save it as 'team' instead, or ask an administrator to add you to that group.")
                    .asException();
        }
        return visibility;
    }

    private static boolean hasRole(Authentication caller, String authority) {
        return caller != null
                && caller.getAuthorities().stream()
                        .anyMatch(granted -> granted.getAuthority().equals(authority));
    }

    /**
     * Whether this preset can still produce a project, and why not when it cannot.
     *
     * <p>Two ways a preset goes stale: a recipe was removed from the catalog, or a pinned version
     * was. Both are answerable from the catalog without rendering anything, which is the point —
     * the alternative is a render that fails halfway with an error about a template.
     */
    String staleReason(Preset preset) {
        List<String> missing = missingRecipes(preset);
        if (!missing.isEmpty()) {
            return "This preset selects %s, which %s no longer in the catalog. Edit the preset to "
                            .formatted(String.join(" and ", missing), missing.size() == 1 ? "is" : "are")
                    + "choose a replacement.";
        }

        List<String> gone = missingPins(preset);
        if (!gone.isEmpty()) {
            return "This preset pins %s, and the catalog no longer offers %s. Switch it to tracking "
                            .formatted(String.join(", ", gone), gone.size() == 1 ? "that version" : "those versions")
                    + "the latest catalog, or pin a version that still exists.";
        }
        return null;
    }

    /** Recipes the selection names that the catalog has since dropped. */
    private List<String> missingRecipes(Preset preset) {
        return recipeIdsIn(selection(preset)).stream()
                .filter(id -> catalog.find(RecipeId.of(id)).isEmpty())
                .sorted()
                .toList();
    }

    /** Pins whose exact version the catalog no longer offers. Empty unless the policy is pinned. */
    private List<String> missingPins(Preset preset) {
        if (preset.versionPolicy() != VersionPolicy.PINNED) {
            return List.of();
        }
        List<String> gone = new ArrayList<>();
        pinnedVersions(preset).forEach((recipeId, version) -> {
            Optional<Recipe> recipe = catalog.find(RecipeId.of(recipeId));
            if (recipe.isEmpty() || !recipe.get().version().toString().equals(version)) {
                gone.add(recipeId + " " + version);
            }
        });
        return List.copyOf(gone);
    }

    /**
     * Refuses to generate from a preset that cannot produce a project.
     *
     * <p>The typed error names the thing that is actually missing, not the preset — a user whose
     * saved stack lost its backend needs to be told which recipe went, and {@code UNKNOWN_RECIPE}
     * already suggests the nearest surviving id. Failing here rather than mid-render is the whole
     * point: a render failure would report a template, which is nobody's mistake.
     */
    private void requireUsable(Preset preset) {
        List<String> missing = missingRecipes(preset);
        if (!missing.isEmpty()) {
            throw GenerationError.unknownRecipe(missing.getFirst(), catalog.recipeIds())
                    .asException();
        }

        List<String> gone = missingPins(preset);
        if (!gone.isEmpty()) {
            throw GenerationError.invalidIdentifier(
                            "pinnedRecipes",
                            "'" + String.join(", ", gone) + "'",
                            "must pin versions the catalog still offers",
                            "Switch this preset to tracking the latest catalog, or pin a version that still "
                                    + "exists. §7 keeps pinning for compliance cases; tracking is the default "
                                    + "because a preset frozen on an old framework is a trap.")
                    .asException();
        }
    }

    // --- plumbing ----------------------------------------------------------

    private Preset requireVisible(UUID id, UUID caller) {
        Preset preset = presets.findById(id).orElseThrow(PresetService::notFound);
        if (preset.ownerId().equals(caller) || preset.visibility() != Visibility.PRIVATE) {
            return preset;
        }
        // Not 403: somebody else's private preset should be indistinguishable from one that was
        // never there, or the endpoint becomes a way to discover that it exists.
        throw notFound();
    }

    private Preset requireOwned(UUID id, UUID owner) {
        Preset preset = presets.findById(id).orElseThrow(PresetService::notFound);
        if (!preset.ownerId().equals(owner)) {
            throw notFound();
        }
        return preset;
    }

    private static SelectionValidationException notFound() {
        return new SelectionValidationException("id", "No preset with that id.");
    }

    /** One entry per name: the newest revision is the preset, the rest are its history. */
    private static List<Preset> latestRevisions(List<Preset> all) {
        Map<String, Preset> newest = new LinkedHashMap<>();
        all.forEach(preset -> newest.merge(preset.name(), preset, (a, b) -> a.revision() >= b.revision() ? a : b));
        return List.copyOf(newest.values());
    }

    private PresetResponse describe(Preset preset, UUID caller) {
        GenerateRequest selection = selection(preset);
        return new PresetResponse(
                preset.id(),
                preset.name(),
                preset.description(),
                preset.visibility().wireName(),
                selection,
                preset.versionPolicy().wireName(),
                pinnedVersions(preset),
                preset.revision(),
                preset.ownerId().equals(caller),
                recipeIdsIn(selection),
                staleReason(preset),
                preset.createdAt(),
                preset.updatedAt());
    }

    /**
     * The recipe ids a selection names.
     *
     * <p>Option values that look like recipe ids, which is the same rule the parse stage uses to
     * reject a typo. It is a heuristic, and it is the right one here: a preset that named a recipe
     * the catalog has since dropped is exactly a value that used to resolve and no longer does.
     */
    private List<String> recipeIdsIn(GenerateRequest selection) {
        List<String> ids = new ArrayList<>();
        selection.options().forEach((ignored, value) -> {
            if (value instanceof String text && (catalog.recipeIds().contains(text) || text.contains("-"))) {
                ids.add(text);
            }
        });
        return List.copyOf(ids);
    }

    private GenerateRequest selection(Preset preset) {
        try {
            return json.readValue(preset.selection(), GenerateRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Preset " + preset.id() + " holds a selection that will not parse", e);
        }
    }

    /**
     * Stores the selection with its schema version filled in.
     *
     * <p>A client may leave {@code schemaVersion} out and have it default, which is fine for a
     * request that is answered immediately. A preset outlives the schema it was written against,
     * and §7's migrations need to know which one that was — a row full of nulls would leave the
     * migration guessing, and guessing wrong in the direction of "probably the current one".
     */
    private String serialize(GenerateRequest selection) {
        GenerateRequest stamped = new GenerateRequest(
                selection.schemaVersion() == null
                        ? dev.kitbash.core.selection.SelectionEnvelope.CURRENT_SCHEMA_VERSION
                        : selection.schemaVersion(),
                selection.projectName(),
                selection.options(),
                selection.variables());
        try {
            return json.writeValueAsString(stamped);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the selection", e);
        }
    }

    private Map<String, String> pinnedVersions(Preset preset) {
        if (preset.pinnedRecipes() == null || preset.pinnedRecipes().isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(preset.pinnedRecipes(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Preset " + preset.id() + " holds pins that will not parse", e);
        }
    }

    /**
     * What a {@code pinned} preset pins.
     *
     * <p>When the client sends nothing, the pins are taken from the catalog as it is now — which is
     * what "pin this" means at the moment somebody asks for it. Only the recipes the selection
     * actually names are recorded; anything else resolves fresh, and a mixed policy is normal.
     */
    private String pinned(PresetRequest request) {
        if (policyOf(request) != VersionPolicy.PINNED) {
            return null;
        }
        Map<String, String> pins = new LinkedHashMap<>();
        if (request.pinnedRecipes() != null && !request.pinnedRecipes().isEmpty()) {
            pins.putAll(request.pinnedRecipes());
        } else {
            recipeIdsIn(requireSelection(request)).forEach(id -> catalog.find(RecipeId.of(id))
                    .ifPresent(recipe -> pins.put(id, recipe.version().toString())));
        }
        return serializePins(pins);
    }

    private String serializePins(Map<String, String> pins) {
        try {
            return json.writeValueAsString(pins);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the pins", e);
        }
    }

    private VersionPolicy policyOf(PresetRequest request) {
        return request.versionPolicy() == null || request.versionPolicy().isBlank()
                ? VersionPolicy.TRACK_LATEST
                : parse(request.versionPolicy(), VersionPolicy::of, "versionPolicy", "track_latest or pinned");
    }

    private static <T> T parse(String value, java.util.function.Function<String, T> of, String field, String rule) {
        try {
            return of.apply(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw GenerationError.invalidIdentifier(
                            field, "'" + value + "'", "must be " + rule, "Send one of the values the API documents.")
                    .asException();
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw GenerationError.invalidIdentifier(
                            "name",
                            "''",
                            "must not be empty",
                            "A preset is found by name in the list, so it needs one. It does not have to match the "
                                    + "project name — a preset outlives any single project.")
                    .asException();
        }
        return name.strip();
    }

    private static GenerateRequest requireSelection(PresetRequest request) {
        if (request.selection() == null) {
            throw GenerationError.invalidIdentifier(
                            "selection",
                            "null",
                            "must be a selection envelope",
                            "Send the same envelope /generate takes; the preset stores it unresolved.")
                    .asException();
        }
        return request.selection();
    }

    /**
     * The {@code owner_id} column's value for this caller.
     *
     * <p>§18 has no user table: the subject of the token is the owner, and §10 types that column as
     * a uuid, so a provider whose subjects are opaque strings gets a stable derived one.
     */
    private static UUID ownerOf(Authentication caller) {
        if (caller == null || caller.getName() == null) {
            throw new IllegalStateException(
                    "A preset was written by nobody, which cannot happen behind an authenticated endpoint.");
        }
        if (caller.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt
                && jwt.getSubject() != null) {
            return Caller.asUuid(jwt.getSubject());
        }
        return Caller.asUuid(caller.getName());
    }
}
