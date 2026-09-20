package dev.kitbash.core.recipe;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Every recipe this server knows about, held immutably in memory with the digest that identifies
 * the set (§7, §10).
 *
 * <p>§10 is emphatic that the catalog is <b>not in the database</b>: recipes live in the repo, are
 * validated at boot and held here, so they stay reviewable, diffable and versioned with the code
 * that renders them. A DB-backed catalog would put every recipe change behind a migration and an
 * admin CRUD screen for no gain.
 *
 * <p>The type lives in {@code core} rather than in {@code catalog} because the resolver's signature
 * is {@code (Catalog, Selection) -> Resolution} and the resolver has to stay pure and Spring-free
 * (§6, §8). {@code catalog} is what *builds* one of these; {@code core} is what reasons about it.
 */
public final class Catalog {

    private final List<Recipe> recipes;
    private final Map<RecipeId, Recipe> byId;
    private final Map<Capability, List<Recipe>> providers;
    private final Map<String, Slot> slots;
    private final Map<String, List<Recipe>> bySlot;
    private final List<OptionGroup> groups;
    private final List<VariableSpec> variables;
    private final String digest;

    private Catalog(List<Recipe> recipes, String digest, List<OptionGroup> groups, List<VariableSpec> variables) {
        this.recipes = List.copyOf(recipes);
        this.digest = Objects.requireNonNull(digest, "digest");
        this.groups = List.copyOf(groups);
        this.variables = List.copyOf(variables);

        Map<String, Slot> declaredSlots = new LinkedHashMap<>();
        this.groups.stream()
                .sorted(Comparator.comparingInt(OptionGroup::order).thenComparing(OptionGroup::id))
                .forEach(group -> group.slots().forEach(slot -> declaredSlots.put(slot.id(), slot)));
        this.slots = Map.copyOf(declaredSlots);

        Map<String, List<Recipe>> inSlot = new LinkedHashMap<>();
        this.recipes.forEach(recipe -> {
            if (recipe.slot() != null) {
                inSlot.computeIfAbsent(recipe.slot(), ignored -> new java.util.ArrayList<>())
                        .add(recipe);
            }
        });
        Map<String, List<Recipe>> frozenSlots = new LinkedHashMap<>();
        inSlot.forEach((slot, list) -> frozenSlots.put(slot, List.copyOf(list)));
        this.bySlot = Map.copyOf(frozenSlots);
        Map<RecipeId, Recipe> index = new LinkedHashMap<>();
        Map<Capability, List<Recipe>> byCapability = new LinkedHashMap<>();
        for (Recipe recipe : this.recipes) {
            index.put(recipe.id(), recipe);
            for (Capability capability : recipe.provides()) {
                byCapability
                        .computeIfAbsent(capability, ignored -> new java.util.ArrayList<>())
                        .add(recipe);
            }
        }
        this.byId = Map.copyOf(index);
        Map<Capability, List<Recipe>> frozen = new LinkedHashMap<>();
        byCapability.forEach((capability, list) -> frozen.put(capability, List.copyOf(list)));
        this.providers = Map.copyOf(frozen);
    }

    /**
     * Recipes are sorted by id here, once, so every downstream consumer — the resolver's tie-break,
     * the metadata document, the digest — sees the same order without having to ask for it.
     */
    public static Catalog of(Collection<Recipe> recipes, String digest) {
        return of(recipes, digest, List.of(), List.of());
    }

    public static Catalog of(
            Collection<Recipe> recipes, String digest, List<OptionGroup> groups, List<VariableSpec> variables) {
        return new Catalog(
                recipes.stream().sorted(Comparator.comparing(Recipe::id)).toList(), digest, groups, variables);
    }

    public static Catalog empty() {
        return new Catalog(List.of(), "sha256:" + "0".repeat(64), List.of(), List.of());
    }

    /** The wizard's sections, in display order (§8). */
    public List<OptionGroup> groups() {
        return groups;
    }

    /** The free-text inputs, with the labels, help and patterns a client renders them from. */
    public List<VariableSpec> variables() {
        return variables;
    }

    public Optional<Slot> slot(String id) {
        return Optional.ofNullable(slots.get(id));
    }

    public Set<String> slotIds() {
        return slots.keySet();
    }

    /** The recipes a slot offers, in id order. Empty means a slot nobody fills. */
    public List<Recipe> recipesInSlot(String slotId) {
        return bySlot.getOrDefault(slotId, List.of());
    }

    /** sha256 over the sorted set of (recipeId, version, contentHash) — §7. */
    public String digest() {
        return digest;
    }

    public List<Recipe> recipes() {
        return recipes;
    }

    public int size() {
        return recipes.size();
    }

    public Optional<Recipe> find(RecipeId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Set<String> recipeIds() {
        return recipes.stream()
                .map(recipe -> recipe.id().value())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** Every recipe providing a capability, in id order. Empty means nothing satisfies it. */
    public List<Recipe> providersOf(Capability capability) {
        return providers.getOrDefault(capability, List.of());
    }

    public Set<Capability> allCapabilities() {
        return Set.copyOf(providers.keySet());
    }
}
