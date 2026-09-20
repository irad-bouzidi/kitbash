package dev.kitbash.catalog.metadata;

import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.OptionGroup;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.Slot;
import dev.kitbash.core.recipe.VariableSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a loaded catalog into the document §8 serves.
 *
 * <p>Pure, and in {@code catalog} rather than in {@code api}, for the reason {@code kitbash-17}
 * will need: the CLI's {@code kitbash catalog --json} has to produce the same bytes as {@code GET
 * /api/v1/metadata}, and the cheapest guarantee of that is one assembler with two callers.
 */
public final class MetadataAssembler {

    private MetadataAssembler() {}

    public static MetadataDocument assemble(Catalog catalog) {
        return new MetadataDocument(
                MetadataDocument.SCHEMA_VERSION,
                catalog.digest(),
                catalog.size(),
                groups(catalog),
                variables(catalog),
                recipes(catalog));
    }

    private static List<MetadataDocument.Group> groups(Catalog catalog) {
        List<MetadataDocument.Group> groups = new ArrayList<>();
        for (OptionGroup group : catalog.groups()) {
            List<MetadataDocument.Option> options = new ArrayList<>();
            for (Slot slot : group.slots()) {
                options.add(slotOption(catalog, slot));
                // The options a recipe declares live in the same group as the slot that offers the
                // recipe: `architecture` belongs beside `backend`, not in a section of its own.
                catalog.recipesInSlot(slot.id()).forEach(recipe -> recipe.options()
                        .forEach(option -> options.add(recipeOption(recipe, option))));
            }
            groups.add(new MetadataDocument.Group(group.id(), group.label(), group.help(), group.order(), options));
        }
        return groups;
    }

    private static MetadataDocument.Option slotOption(Catalog catalog, Slot slot) {
        List<MetadataDocument.Choice> choices = catalog.recipesInSlot(slot.id()).stream()
                .map(MetadataAssembler::choice)
                .toList();
        return new MetadataDocument.Option(
                slot.id(),
                slot.type().wireName(),
                slot.label(),
                slot.help(),
                slot.required(),
                slot.isEnum() ? null : slot.defaultOn(),
                null,
                choices);
    }

    private static MetadataDocument.Option recipeOption(Recipe recipe, OptionSpec option) {
        return new MetadataDocument.Option(
                option.id(),
                option.type().wireName(),
                option.label(),
                option.help(),
                false,
                option.defaultValue().templateValue(),
                recipe.id().value(),
                option.values().stream()
                        .map(value -> new MetadataDocument.Choice(
                                value, value, null, null, null, List.of(), List.of(), List.of(), null))
                        .toList());
    }

    private static MetadataDocument.Choice choice(Recipe recipe) {
        return new MetadataDocument.Choice(
                recipe.id().value(),
                recipe.label(),
                recipe.id().value(),
                recipe.version().toString(),
                recipe.frameworkVersion(),
                names(recipe.provides()),
                names(recipe.requires()),
                recipe.conflictsWith().stream().map(RecipeId::value).sorted().toList(),
                null);
    }

    /**
     * Variables, each naming the recipes that require it.
     *
     * <p>That list is what lets a client ask for a package name only when something needs one,
     * rather than asking for everything always and rejecting half of it at submit time.
     */
    private static List<MetadataDocument.Variable> variables(Catalog catalog) {
        List<MetadataDocument.Variable> variables = new ArrayList<>();
        for (VariableSpec spec : catalog.variables()) {
            Set<String> requiredBy = new TreeSet<>();
            catalog.recipes().forEach(recipe -> {
                if (recipe.requiredVariables().contains(spec.id())) {
                    requiredBy.add(recipe.id().value());
                }
            });
            variables.add(new MetadataDocument.Variable(
                    spec.id(),
                    spec.label(),
                    spec.help(),
                    spec.pattern(),
                    spec.defaultValue(),
                    List.copyOf(requiredBy)));
        }
        return variables;
    }

    private static List<MetadataDocument.RecipeSummary> recipes(Catalog catalog) {
        return catalog.recipes().stream()
                .map(recipe -> new MetadataDocument.RecipeSummary(
                        recipe.id().value(),
                        recipe.label(),
                        recipe.kind().wireName(),
                        recipe.slot(),
                        recipe.version().toString(),
                        recipe.frameworkVersion(),
                        names(recipe.provides()),
                        names(recipe.requires()),
                        recipe.conflictsWith().stream()
                                .map(RecipeId::value)
                                .sorted()
                                .toList()))
                .toList();
    }

    private static List<String> names(Set<Capability> capabilities) {
        return capabilities.stream().map(Capability::name).sorted().toList();
    }
}
