package dev.kitbash.core.resolve;

import dev.kitbash.core.error.ErrorDetail;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.Stage;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.selection.OptionValue;
import dev.kitbash.core.selection.Selection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The pure function at the centre of the product: a selection goes in, an ordered recipe list plus
 * diagnostics come out.
 *
 * <p>No I/O, no Spring, no clock, no randomness. The signature is {@code (Catalog, Selection) ->
 * Resolution} and stays that way: §17 singles this component out as the one with real logic and the
 * one where regressions stay invisible until a user's project fails to compile, so its test suite is
 * as much the deliverable as the code — and it only stays fast enough to run on every save if
 * nothing here needs constructing.
 *
 * <p><b>How a recipe gets selected.</b> Three rules, and nothing else:
 *
 * <ol>
 *   <li>An option whose value names a recipe id selects that recipe; a multi-select selects each of
 *       its values.
 *   <li>A boolean option set to {@code true} whose id names a capability demands that capability,
 *       which implied expansion then satisfies. This is how {@code "docker": true} reaches the
 *       container recipe without the envelope, the wizard or this class naming it.
 *   <li>Everything else is configuration, read by templates and by {@code when} expressions.
 * </ol>
 *
 * <p>There is no compatibility matrix in here, which is the whole of §4. Recipes declare what they
 * provide and require and the selected set is checked structurally; a hand-written table of legal
 * combinations is what the alternative design degenerates into, and it rots the first time somebody
 * adds a recipe and forgets a row.
 */
public final class Resolver {

    /** Guards a manifest set that somehow keeps implying new work; a settled graph needs a handful. */
    private static final int MAX_EXPANSION_PASSES = 64;

    private final Catalog catalog;

    public Resolver(Catalog catalog) {
        this.catalog = catalog;
    }

    public static Resolution resolve(Catalog catalog, Selection selection) {
        return new Resolver(catalog).resolve(selection);
    }

    public Resolution resolve(Selection selection) {
        List<GenerationError> conflicts = new ArrayList<>();
        List<ResolutionWarning> warnings = new ArrayList<>();

        Reading reading = read(selection);
        Set<RecipeId> selected = new LinkedHashSet<>(reading.selected());
        Set<RecipeId> implied = expand(selected, reading, conflicts);

        List<Recipe> chosen = selected.stream().map(this::require).toList();
        checkConflicts(chosen, reading, conflicts);

        checkRequiredVariables(chosen, selection, conflicts);
        Map<String, OptionValue> effective = effectiveOptions(chosen, selection, reading, conflicts, warnings);
        Set<Capability> capabilities = chosen.stream()
                .flatMap(recipe -> recipe.provides().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new Resolution(order(chosen, conflicts), implied, effective, capabilities, conflicts, warnings);
    }

    // --- 1. what the caller asked for ---------------------------------------

    /**
     * The selection, split into what it selects and what it merely configures — with the option id
     * behind each decision kept, because §8 wants a diagnostic to name the control the user can
     * change rather than the machinery that rejected it.
     */
    private record Reading(
            Set<RecipeId> selected, Map<RecipeId, String> recipeSlots, Map<Capability, String> capabilitySlots) {

        Set<String> slotOptions() {
            Set<String> slots = new LinkedHashSet<>(recipeSlots.values());
            slots.addAll(capabilitySlots.values());
            return slots;
        }
    }

    private Reading read(Selection selection) {
        Set<RecipeId> selected = new LinkedHashSet<>();
        Map<RecipeId, String> recipeSlots = new LinkedHashMap<>();
        Map<Capability, String> capabilitySlots = new LinkedHashMap<>();
        Set<Capability> known = catalog.allCapabilities();

        selection.canonical().options().forEach((optionId, value) -> {
            switch (value) {
                case OptionValue.Text text ->
                    asRecipe(text.value()).ifPresent(id -> {
                        selected.add(id);
                        recipeSlots.putIfAbsent(id, optionId);
                    });
                case OptionValue.Multi multi ->
                    multi.values().forEach(entry -> asRecipe(entry).ifPresent(id -> {
                        selected.add(id);
                        recipeSlots.putIfAbsent(id, optionId);
                    }));
                case OptionValue.Flag flag -> {
                    Capability capability = asCapability(optionId);
                    if (flag.value() && capability != null && known.contains(capability)) {
                        capabilitySlots.putIfAbsent(capability, optionId);
                    }
                }
            }
        });
        return new Reading(selected, recipeSlots, capabilitySlots);
    }

    // --- 2. implied expansion -----------------------------------------------

    /**
     * Pulls in what the selection implies. §8 is specific about the rule: a capability with exactly
     * one provider is selected automatically, and one with several is returned as a choice rather
     * than guessed at. Guessing is how somebody ends up with jOOQ because it sorted before JPA.
     */
    private Set<RecipeId> expand(Set<RecipeId> selected, Reading reading, List<GenerationError> conflicts) {
        Set<RecipeId> implied = new LinkedHashSet<>();
        Set<Capability> reported = new LinkedHashSet<>();

        for (int pass = 0; pass < MAX_EXPANSION_PASSES; pass++) {
            Set<Capability> satisfied = selected.stream()
                    .map(this::require)
                    .flatMap(recipe -> recipe.provides().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // Capability -> who is asking for it, so the diagnostic can say "auth requires a
            // backend" rather than "capability http-server unsatisfied".
            Map<Capability, String> missing = new LinkedHashMap<>();
            reading.capabilitySlots().forEach((capability, optionId) -> {
                if (!satisfied.contains(capability) && !reported.contains(capability)) {
                    missing.putIfAbsent(capability, "option '" + optionId + "'");
                }
            });
            for (RecipeId id : new TreeSet<>(selected)) {
                Recipe recipe = require(id);
                for (Capability capability : recipe.requires()) {
                    if (!satisfied.contains(capability) && !reported.contains(capability)) {
                        missing.putIfAbsent(capability, recipe.id().value());
                    }
                }
            }
            if (missing.isEmpty()) {
                return implied;
            }

            boolean progressed = false;
            for (Map.Entry<Capability, String> entry : missing.entrySet()) {
                List<Recipe> providers = catalog.providersOf(entry.getKey());
                if (providers.size() == 1 && selected.add(providers.get(0).id())) {
                    implied.add(providers.get(0).id());
                    progressed = true;
                } else if (providers.size() != 1) {
                    conflicts.add(unsatisfied(entry.getKey(), providers, entry.getValue(), reading));
                    reported.add(entry.getKey());
                    progressed = true;
                }
            }
            if (!progressed) {
                return implied;
            }
        }
        throw new IllegalStateException("Recipe expansion did not settle after " + MAX_EXPANSION_PASSES
                + " passes; the catalog's requires graph is pathological.");
    }

    /**
     * Zero providers or several, reported the same way: the user has a choice to make, and the
     * option they make it on is named. The capability name doubles as the slot id, which is the
     * convention the metadata document follows (§8, docs/recipe-format.md).
     */
    private GenerationError unsatisfied(
            Capability capability, List<Recipe> providers, String requiredBy, Reading reading) {
        String optionId = providers.stream()
                .map(provider -> reading.recipeSlots().get(provider.id()))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseGet(() -> reading.capabilitySlots().getOrDefault(capability, capability.name()));

        String message = providers.isEmpty()
                ? requiredBy + " requires '" + capability + "' and nothing in this catalog provides it."
                : requiredBy + " requires '" + capability + "', and more than one recipe provides it.";
        String hint = providers.isEmpty()
                ? "No recipe provides '" + capability + "'. The loader should have refused to start, "
                        + "so this is a catalog bug rather than a bad selection."
                : "Choose one for '" + optionId + "': "
                        + providers.stream()
                                .map(recipe -> recipe.label() + " (" + recipe.id() + ")")
                                .collect(Collectors.joining(", "));

        return new GenerationError.CapabilityUnsatisfied(
                new ErrorDetail(Stage.RESOLVE, requiredBy, null, message, hint, null),
                capability.name(),
                requiredBy,
                optionId);
    }

    // --- 3. structural checks ------------------------------------------------

    private static void checkConflicts(List<Recipe> chosen, Reading reading, List<GenerationError> conflicts) {
        Set<RecipeId> present = chosen.stream().map(Recipe::id).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> reported = new LinkedHashSet<>();

        for (Recipe recipe : chosen) {
            for (RecipeId other : recipe.conflictsWith()) {
                if (!present.contains(other)) {
                    continue;
                }
                // Conflicts are symmetric and both manifests usually declare them. Report the pair
                // once, keyed on the sorted pair, or the wizard shows one problem twice.
                String key = recipe.id().compareTo(other) <= 0 ? recipe.id() + "|" + other : other + "|" + recipe.id();
                if (reported.add(key)) {
                    conflicts.add(GenerationError.conflict(
                            recipe.id().value(),
                            other.value(),
                            reading.recipeSlots()
                                    .getOrDefault(recipe.id(), recipe.kind().wireName())));
                }
            }
        }
    }

    /**
     * Every variable a selected recipe declares has to be supplied.
     *
     * <p>Checked here rather than left to the renderer, because the renderer's report is a Pebble
     * line number in a template the caller has never seen. The manifest already says which recipe
     * needs what; this is where that gets used.
     */
    private static void checkRequiredVariables(
            List<Recipe> chosen, Selection selection, List<GenerationError> conflicts) {
        Set<String> reported = new LinkedHashSet<>();
        for (Recipe recipe : chosen) {
            for (String variable : recipe.requiredVariables()) {
                String value = selection.variables().get(variable);
                if ((value == null || value.isBlank()) && reported.add(variable)) {
                    conflicts.add(GenerationError.invalidIdentifier(
                            variable,
                            "",
                            "required by " + recipe.id() + " and not supplied",
                            "Add \"" + variable + "\" to the selection's variables."));
                }
            }
        }
    }

    /**
     * Defaults applied, supplied values type-checked. This is the "effective option set" §8 says
     * {@code /validate} returns, and it is also what {@code when} expressions evaluate against — so
     * a default that never made it in here is a file set that silently stops shipping.
     */
    private static Map<String, OptionValue> effectiveOptions(
            List<Recipe> chosen,
            Selection selection,
            Reading reading,
            List<GenerationError> conflicts,
            List<ResolutionWarning> warnings) {
        Map<String, OptionSpec> declared = new LinkedHashMap<>();
        for (Recipe recipe : chosen) {
            recipe.options().forEach(spec -> declared.putIfAbsent(spec.id(), spec));
        }

        Map<String, OptionValue> effective = new LinkedHashMap<>();
        declared.forEach((optionId, spec) -> effective.put(optionId, spec.defaultValue()));

        selection.canonical().options().forEach((optionId, value) -> {
            OptionSpec spec = declared.get(optionId);
            if (spec == null) {
                effective.put(optionId, value);
                if (!reading.slotOptions().contains(optionId)) {
                    // Carried rather than rejected: the envelope is flat and forward-compatible
                    // (§7), and an option belonging to a recipe the user just deselected is the
                    // ordinary case. Carried *silently* is how somebody believes a setting applied.
                    warnings.add(ResolutionWarning.of(
                            optionId,
                            "'" + optionId + "' is not an option of any selected recipe, so nothing reads it.",
                            "Remove it, or select the recipe that declares it."));
                }
                return;
            }
            if (!spec.accepts(value)) {
                conflicts.add(GenerationError.invalidIdentifier(
                        optionId,
                        value.toJson(),
                        "not a value '" + optionId + "' accepts",
                        spec.values().isEmpty()
                                ? "Expected a " + spec.type().wireName() + " value."
                                : "Choose one of: " + String.join(", ", spec.values())));
                return;
            }
            effective.put(optionId, value);
        });
        return effective;
    }

    // --- 4. ordering ---------------------------------------------------------

    /**
     * Base → backend → frontend → features → infra → CI, ties broken by recipe id (§4).
     *
     * <p><b>Kind is the order; {@code requires} is not.</b> That distinction is easy to get wrong
     * and produces a spurious cycle the first time it is: {@code backend-spring-java} requires
     * {@code database}, and {@code db-postgres-flyway} requires {@code jvm-project} — which the
     * backend provides. Read as ordering edges those two are a loop, and the catalog would refuse
     * to resolve. Read correctly they are not: {@code requires} says <i>this recipe needs that
     * capability present in the selection</i>, while what has to come first is fixed by kind, and
     * the database recipe patches a project the backend has already laid down.
     *
     * <p>So recipes are grouped by kind, and the dependency graph is consulted only <i>within</i> a
     * group, where kind gives no order and one feature genuinely can build on another. Ties there
     * fall back to recipe id, which is what keeps the order total — without that, two independent
     * recipes could come out either way round and the zip would stop being byte-identical between
     * runs (§4), taking the cache key and the reproducibility guarantee with it.
     *
     * <p>A cycle can therefore only be an intra-kind one, which is exactly the case worth reporting:
     * two features that each require something the other provides is a manifest bug, not an
     * artefact of how the graph was read.
     */
    private static List<Recipe> order(List<Recipe> chosen, List<GenerationError> conflicts) {
        Comparator<Recipe> pipelineOrder = Comparator.comparing(Recipe::kind).thenComparing(Recipe::id);
        List<Recipe> ordered = new ArrayList<>(chosen.size());

        for (RecipeKind kind : RecipeKind.values()) {
            List<Recipe> group = chosen.stream()
                    .filter(recipe -> recipe.kind() == kind)
                    .sorted(Comparator.comparing(Recipe::id))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            List<Recipe> sorted = topologicallySort(group, conflicts);
            ordered.addAll(sorted);
        }
        return ordered.size() == chosen.size()
                ? List.copyOf(ordered)
                // A cycle was reported; still return a stack so a caller rendering the resolution
                // shows the selection beside the error rather than an empty panel.
                : chosen.stream().sorted(pipelineOrder).toList();
    }

    /** Kahn's algorithm over a priority queue, so the result is both correct and total. */
    private static List<Recipe> topologicallySort(List<Recipe> group, List<GenerationError> conflicts) {
        Map<RecipeId, Recipe> byId = new LinkedHashMap<>();
        group.forEach(recipe -> byId.put(recipe.id(), recipe));

        Map<RecipeId, Set<RecipeId>> dependencies = new LinkedHashMap<>();
        Map<RecipeId, Set<RecipeId>> dependents = new LinkedHashMap<>();
        group.forEach(recipe -> {
            dependencies.put(recipe.id(), new TreeSet<>());
            dependents.put(recipe.id(), new TreeSet<>());
        });
        for (Recipe recipe : group) {
            for (Capability capability : recipe.requires()) {
                for (Recipe provider : group) {
                    if (!provider.id().equals(recipe.id()) && provider.provides(capability)) {
                        dependencies.get(recipe.id()).add(provider.id());
                        dependents.get(provider.id()).add(recipe.id());
                    }
                }
            }
        }

        PriorityQueue<Recipe> ready = new PriorityQueue<>(Comparator.comparing(Recipe::id));
        group.stream().filter(recipe -> dependencies.get(recipe.id()).isEmpty()).forEach(ready::add);

        List<Recipe> ordered = new ArrayList<>(group.size());
        while (!ready.isEmpty()) {
            Recipe next = ready.poll();
            ordered.add(next);
            for (RecipeId dependent : dependents.get(next.id())) {
                Set<RecipeId> remaining = dependencies.get(dependent);
                remaining.remove(next.id());
                if (remaining.isEmpty()) {
                    ready.add(byId.get(dependent));
                }
            }
        }

        if (ordered.size() != group.size()) {
            Set<RecipeId> emitted = ordered.stream().map(Recipe::id).collect(Collectors.toSet());
            List<RecipeId> stuck = group.stream()
                    .map(Recipe::id)
                    .filter(id -> !emitted.contains(id))
                    .sorted()
                    .toList();
            conflicts.add(GenerationError.cycle(cycleThrough(stuck, dependencies)));
        }
        return ordered;
    }

    /** Walks what is left of the graph to name the members of the cycle, not merely its existence. */
    private static List<String> cycleThrough(List<RecipeId> stuck, Map<RecipeId, Set<RecipeId>> dependencies) {
        if (stuck.isEmpty()) {
            return List.of();
        }
        Set<RecipeId> candidates = Set.copyOf(stuck);
        Deque<RecipeId> path = new ArrayDeque<>();
        Set<RecipeId> onPath = new LinkedHashSet<>();

        RecipeId current = stuck.get(0);
        while (current != null && onPath.add(current)) {
            path.addLast(current);
            current = dependencies.getOrDefault(current, Set.of()).stream()
                    .filter(candidates::contains)
                    .findFirst()
                    .orElse(null);
        }
        if (current == null) {
            return stuck.stream().map(RecipeId::value).toList();
        }

        // `current` is the node the walk returned to: the cycle is the path from it onwards, closed
        // by naming it a second time so the shape reads as a loop.
        List<String> members = new ArrayList<>();
        boolean inCycle = false;
        for (RecipeId id : path) {
            inCycle = inCycle || id.equals(current);
            if (inCycle) {
                members.add(id.value());
            }
        }
        members.add(current.value());
        return members;
    }

    // --- helpers -------------------------------------------------------------

    private Optional<RecipeId> asRecipe(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return catalog.find(RecipeId.of(value)).map(Recipe::id);
        } catch (IllegalArgumentException notAnId) {
            // 'layered' and 'hexagonal' land here, which is right: an option value that is not a
            // recipe id is configuration, not a selection.
            return Optional.empty();
        }
    }

    private static Capability asCapability(String optionId) {
        try {
            return Capability.of(optionId);
        } catch (IllegalArgumentException notACapability) {
            return null;
        }
    }

    private Recipe require(RecipeId id) {
        return catalog.find(id).orElseThrow(() -> GenerationError.unknownRecipe(id.value(), catalog.recipeIds())
                .asException());
    }
}
