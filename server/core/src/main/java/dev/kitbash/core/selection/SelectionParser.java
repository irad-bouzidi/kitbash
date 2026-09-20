package dev.kitbash.core.selection;

import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.Recipe;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stage 1 of §6: an envelope off the wire becomes a {@link Selection} the rest of the pipeline can
 * trust, or a typed rejection.
 *
 * <p>Two things happen here and nowhere else. The envelope is migrated to the current schema
 * version and its option values are typed (§7). And an option value that was <i>meant</i> to name a
 * recipe but names nothing is rejected, rather than being carried through as configuration.
 *
 * <p>That second check needs explaining, because there is no field marking an option as a recipe
 * slot. A value that looks like a recipe id — lowercase kebab-case with a hyphen — and is neither a
 * recipe in this catalog nor a value any option declares, is a typo. Carrying it silently is worse
 * than it sounds: {@code backend-spring-jva} would resolve to a project with no backend, and the
 * user would find out from an empty zip. Values that are legitimately hyphenated option values,
 * such as {@code modular-monolith}, are declared by some recipe's {@code values} list and so pass.
 */
public final class SelectionParser {

    private static final Pattern RECIPE_SHAPED = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)+");

    private SelectionParser() {}

    public static Selection parse(Catalog catalog, SelectionEnvelope envelope) {
        Selection selection = envelope.parse();
        Identifiers.requireProjectName(selection.projectName());
        selection.variables().forEach(SelectionParser::validateVariable);

        Set<String> known = knownValues(catalog);
        selection.options().values().forEach(value -> rejectUnknownRecipes(catalog, known, value));
        return selection;
    }

    private static void validateVariable(String name, String value) {
        switch (name) {
            case "groupId" -> Identifiers.requireGroupId(value);
            case "packageName" -> Identifiers.requirePackageName(value);
            case "javaVersion" -> Identifiers.requireJavaVersion(value);
            default -> {
                // Other variables are recipe-specific and validated by the recipe that declares
                // them; the full hostile-input corpus is kitbash-20.
            }
        }
    }

    private static void rejectUnknownRecipes(Catalog catalog, Set<String> known, OptionValue value) {
        switch (value) {
            case OptionValue.Text text -> reject(catalog, known, text.value());
            case OptionValue.Multi multi -> multi.values().forEach(entry -> reject(catalog, known, entry));
            case OptionValue.Flag ignored -> {
                // A boolean cannot be a mistyped recipe id.
            }
        }
    }

    private static void reject(Catalog catalog, Set<String> known, String value) {
        if (value == null
                || known.contains(value)
                || !RECIPE_SHAPED.matcher(value).matches()) {
            return;
        }
        throw GenerationError.unknownRecipe(value, catalog.recipeIds()).asException();
    }

    /** Every string this catalog can legitimately see: recipe ids, plus every declared option value. */
    private static Set<String> knownValues(Catalog catalog) {
        Set<String> known = new LinkedHashSet<>(catalog.recipeIds());
        for (Recipe recipe : catalog.recipes()) {
            for (OptionSpec option : recipe.options()) {
                List<String> values = option.values();
                known.addAll(values);
            }
        }
        return known;
    }
}
