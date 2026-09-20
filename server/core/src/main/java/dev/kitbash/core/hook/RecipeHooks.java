package dev.kitbash.core.hook;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry, and the ceiling on it.
 *
 * <p>A map in {@code core} rather than a {@code ServiceLoader}: a service-loader mechanism invites
 * exactly the dynamic discovery this design excludes, and there will never be enough hooks for the
 * indirection to pay for itself. §4 caps the list at about three — beyond that, the manifest format
 * is missing a feature and should gain one instead — and {@link #MAX_HOOKS} makes that a test
 * rather than a hope.
 */
public final class RecipeHooks {

    /** §4's "keep this list short", as a number something can assert against. */
    public static final int MAX_HOOKS = 3;

    private static final Map<String, RecipeHook> REGISTERED = register(new VersionCatalogHook());

    private RecipeHooks() {}

    private static Map<String, RecipeHook> register(RecipeHook... hooks) {
        Map<String, RecipeHook> byRecipe = new LinkedHashMap<>();
        for (RecipeHook hook : hooks) {
            RecipeHook previous = byRecipe.put(hook.recipeId(), hook);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two hooks are registered for '" + hook.recipeId() + "'. One recipe, one hook (§4).");
            }
        }
        return Map.copyOf(byRecipe);
    }

    public static List<RecipeHook> all() {
        return List.copyOf(REGISTERED.values());
    }

    public static Optional<RecipeHook> forRecipe(RecipeId id) {
        return Optional.ofNullable(REGISTERED.get(id.value()));
    }

    /**
     * Runs the hook of every selected recipe that declares one, in resolved recipe order.
     *
     * <p>A recipe whose manifest says {@code hook: true} but has nothing registered is a mistake
     * worth catching at plan time: the alternative is a project silently missing whatever the hook
     * was supposed to compute, which surfaces as a build failure in the user's download.
     */
    public static List<PatchOp> contributions(PlanContext context) {
        List<PatchOp> ops = new ArrayList<>();
        for (Recipe recipe : context.recipes()) {
            Optional<RecipeHook> hook = forRecipe(recipe.id());
            if (recipe.hasHook() && hook.isEmpty()) {
                throw new IllegalStateException(recipe.id()
                        + " declares hook: true but no hook is registered for it in core. "
                        + "A recipe directory can declare that a hook exists; it cannot supply one (§4).");
            }
            hook.filter(ignored -> recipe.hasHook())
                    .map(value -> value.contribute(context))
                    .ifPresent(ops::addAll);
        }
        return List.copyOf(ops);
    }
}
