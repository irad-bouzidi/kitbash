package dev.kitbash.core.selection;

import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.VariableSpec;
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
        selection.variables().forEach((name, value) -> validateVariable(catalog, name, value));

        Set<String> known = knownValues(catalog);
        selection.options().values().forEach(value -> rejectUnknownRecipes(catalog, known, value));
        return selection;
    }

    /**
     * Three layers, and every variable goes through at least one.
     *
     * <p>The catalog already declares a {@code pattern} for each variable, and until kitbash-20
     * only the wizard enforced it — which made it a hint rather than a rule, since a crafted
     * request never goes near the wizard. {@code entityName} becomes a Java class name, a SQL
     * table and a URL path; an unvalidated one is arbitrary text written into a file somebody then
     * compiles. So the declared pattern is enforced here, where the catalog is in hand: the
     * catalog declares the rule once, the server enforces it and the client renders it (§9).
     *
     * <p>{@code Identifiers} stays for the three that need more than a regex can say — no regex
     * expresses "not a Java keyword" — and the generic floor catches a variable no manifest
     * declares, which is still a string that ends up in a file.
     */
    private static void validateVariable(Catalog catalog, String name, String value) {
        switch (name) {
            case "groupId" -> Identifiers.requireGroupId(value);
            case "packageName" -> Identifiers.requirePackageName(value);
            case "javaVersion" -> Identifiers.requireJavaVersion(value);
            default -> {
                // Handled by the declared pattern below, or by the floor.
            }
        }

        catalog.variables().stream()
                .filter(spec -> spec.id().equals(name))
                .findFirst()
                .ifPresent(spec -> requireDeclaredPattern(spec, value));

        Identifiers.requireWritableValue(name, value);
    }

    private static void requireDeclaredPattern(VariableSpec spec, String value) {
        if (spec.pattern() == null || spec.pattern().isBlank()) {
            return;
        }
        if (value == null || !Pattern.compile(spec.pattern()).matcher(value).matches()) {
            throw GenerationError.invalidIdentifier(
                            spec.id(),
                            "'" + value + "'",
                            "must match " + spec.pattern(),
                            spec.help() == null
                                    ? "The catalog declares this rule; /api/v1/metadata serves it."
                                    : spec.help())
                    .asException();
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
