package dev.kitbash.core.resolve;

import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.lock.Lock;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.WhenContext;
import dev.kitbash.core.selection.OptionValue;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the resolver produces: the stack a selection actually means, plus everything wrong with it.
 *
 * <p>Designed as an API response even though nothing serves it yet. {@code POST /api/v1/validate}
 * returns exactly this (§8), and the wizard calls that endpoint on every debounced option change —
 * so the shape has to answer "what would I get?" and "what is blocking me?" in one round trip.
 *
 * <p>{@code implied} is separate from {@code recipes} because §9 wants the right rail to show the
 * resolved stack *including* what the resolver added: a user who picked a backend and got a
 * database should be told, not left to discover it in the zip.
 */
public record Resolution(
        List<Recipe> recipes,
        Set<RecipeId> implied,
        Map<String, OptionValue> effectiveOptions,
        Set<Capability> capabilities,
        List<GenerationError> conflicts,
        List<ResolutionWarning> warnings) {

    public Resolution {
        recipes = List.copyOf(recipes);
        implied = Set.copyOf(implied);
        effectiveOptions = Map.copyOf(effectiveOptions);
        capabilities = Set.copyOf(capabilities);
        conflicts = List.copyOf(conflicts);
        warnings = List.copyOf(warnings);
    }

    /** Whether this selection can be generated from. Warnings never block. */
    public boolean valid() {
        return conflicts.isEmpty();
    }

    /** The context {@code when} expressions are evaluated against in the plan stage (§6). */
    public WhenContext whenContext() {
        return WhenContext.of(effectiveOptions, capabilities);
    }

    /** The §7 lock for this resolution: exactly these recipe versions, against this catalog. */
    public Lock lock(String catalogDigest) {
        return Lock.of(recipes, catalogDigest);
    }

    /** The first conflict, which is the one worth showing when only one can be shown. */
    public GenerationError firstConflict() {
        return conflicts.isEmpty() ? null : conflicts.get(0);
    }
}
