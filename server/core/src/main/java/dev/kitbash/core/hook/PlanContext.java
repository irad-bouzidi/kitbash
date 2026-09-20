package dev.kitbash.core.hook;

import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.selection.OptionValue;
import dev.kitbash.core.util.Ordered;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a hook may look at, and nothing it could act through.
 *
 * <p>§4 requires hooks to be pure: no I/O, no shell. The usual way to state that is a comment in an
 * interface, which holds until the first person who needs a file path. So it is stated structurally
 * instead — this record carries the resolved recipes, the effective option set, the validated
 * variables and the capabilities, and offers no filesystem, no network, no clock and no catalog.
 * A hook that wants to misbehave has to reach for a static, and the contract test looks for exactly
 * that.
 *
 * <p>Recipes arrive in resolved order, so a hook that iterates them produces deterministic output
 * without having to sort defensively.
 */
public record PlanContext(
        List<Recipe> recipes,
        Map<String, OptionValue> options,
        Map<String, String> variables,
        Set<Capability> capabilities) {

    public PlanContext {
        recipes = List.copyOf(recipes);
        options = Ordered.copyOf(options);
        variables = Ordered.copyOf(variables);
        capabilities = Set.copyOf(capabilities);
    }

    public boolean selected(RecipeId id) {
        return recipes.stream().anyMatch(recipe -> recipe.id().equals(id));
    }

    public boolean provides(Capability capability) {
        return capabilities.contains(capability);
    }

    public Optional<OptionValue> option(String optionId) {
        return Optional.ofNullable(options.get(optionId));
    }

    public String variable(String name, String fallback) {
        String value = variables.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
