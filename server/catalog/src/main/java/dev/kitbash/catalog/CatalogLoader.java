package dev.kitbash.catalog;

import dev.kitbash.core.hash.Sha256;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.FileRule;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.PatchRule;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.WhenExpression;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads the whole {@code /recipes} tree, validates it, and refuses to produce a catalog if anything
 * is wrong.
 *
 * <p>§10 keeps the catalog out of the database precisely so this can happen at boot: recipes are
 * files in git, so a malformed one should stop the application starting rather than surface as a
 * user's broken download three weeks later. Every check below therefore throws with the file, the
 * field and the fix, and there is a test per rule.
 *
 * <p>The result is immutable. A dev-mode reload is a matter of calling {@link #load} again and
 * swapping the reference — which is also why the digest is computed here and carried on the catalog
 * rather than cached anywhere else.
 */
public final class CatalogLoader {

    private static final String MANIFEST = "recipe.yaml";

    private final ManifestReader reader;

    public CatalogLoader() {
        this(new ManifestReader(ManifestSchema.load()));
    }

    CatalogLoader(ManifestReader reader) {
        this.reader = reader;
    }

    /** Loads and validates every recipe directly under {@code root}. */
    public Catalog load(Path root) {
        List<LoadedRecipe> loaded = readAll(root);
        validate(loaded);
        return Catalog.of(loaded.stream().map(LoadedRecipe::recipe).toList(), digestOf(loaded));
    }

    /** The same load, but keeping the directories the plan stage will read templates from. */
    public List<LoadedRecipe> loadDetailed(Path root) {
        List<LoadedRecipe> loaded = readAll(root);
        validate(loaded);
        return loaded;
    }

    private List<LoadedRecipe> readAll(Path root) {
        if (!Files.isDirectory(root)) {
            throw new RecipeLoadException(
                    root.toString(),
                    null,
                    "is not a directory, so there is no recipe tree to load.",
                    "Point the catalog at the repository's /recipes directory.");
        }
        List<LoadedRecipe> loaded = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            List<Path> directories = children.filter(Files::isDirectory)
                    // `_schema` holds the manifest schema and `_`/`.` prefixes are reserved for
                    // anything else that is not itself a recipe.
                    .filter(path -> !path.getFileName().toString().startsWith("_"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
            for (Path directory : directories) {
                loaded.add(read(root, directory));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list the recipe tree at " + root, e);
        }
        return loaded;
    }

    private LoadedRecipe read(Path root, Path directory) {
        Path manifest = directory.resolve(MANIFEST);
        String displayPath = root.relativize(manifest).toString().replace('\\', '/');
        if (!Files.isRegularFile(manifest)) {
            throw new RecipeLoadException(
                    displayPath,
                    null,
                    "is missing, but " + directory.getFileName() + " looks like a recipe directory.",
                    "Add a recipe.yaml, or move the directory out of the recipe tree if it is not one.");
        }
        Recipe recipe = reader.read(manifest, displayPath);
        if (!recipe.id().value().equals(directory.getFileName().toString())) {
            throw new RecipeLoadException(
                    displayPath,
                    "id",
                    "is '" + recipe.id() + "' but the directory is called '" + directory.getFileName() + "'.",
                    "A recipe's id is its directory name; rename one of them to match the other.");
        }
        return new LoadedRecipe(recipe, directory, contentHashOf(directory));
    }

    // --- validation ----------------------------------------------------------

    static void validate(List<LoadedRecipe> loaded) {
        Map<RecipeId, String> seen = new HashMap<>();
        for (LoadedRecipe entry : loaded) {
            String displayPath = entry.recipe().id() + "/" + MANIFEST;
            String previous = seen.putIfAbsent(entry.recipe().id(), displayPath);
            if (previous != null) {
                throw new RecipeLoadException(
                        displayPath,
                        "id",
                        "'" + entry.recipe().id() + "' is already used by " + previous + ".",
                        "Recipe ids are globally unique; rename one of them.");
            }
        }

        Set<Capability> provided = loaded.stream()
                .flatMap(entry -> entry.recipe().provides().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<RecipeId> known = loaded.stream()
                .map(entry -> entry.recipe().id())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        for (LoadedRecipe entry : loaded) {
            Recipe recipe = entry.recipe();
            String displayPath = recipe.id() + "/" + MANIFEST;
            validateRequires(recipe, provided, displayPath);
            validateConflicts(recipe, known, displayPath);
            validateWhenExpressions(recipe, provided, displayPath);
            validateGlobs(entry, displayPath);
        }
    }

    private static void validateRequires(Recipe recipe, Set<Capability> provided, String displayPath) {
        for (Capability capability : recipe.requires()) {
            if (!provided.contains(capability)) {
                throw new RecipeLoadException(
                        displayPath,
                        "requires",
                        "'" + capability + "' is required but no recipe in this catalog provides it.",
                        "Add a recipe that provides '" + capability + "', or drop the requirement. "
                                + "Recipes coordinate through capabilities, never by naming each other (§4).");
            }
        }
    }

    private static void validateConflicts(Recipe recipe, Set<RecipeId> known, String displayPath) {
        for (RecipeId other : recipe.conflictsWith()) {
            if (!known.contains(other)) {
                throw new RecipeLoadException(
                        displayPath,
                        "conflictsWith",
                        "names '" + other + "', which is not a recipe in this catalog.",
                        "Remove the entry, or fix the id. A conflict with a recipe that does not exist "
                                + "is dead configuration that quietly stops protecting anything.");
            }
        }
    }

    private static void validateWhenExpressions(Recipe recipe, Set<Capability> provided, String displayPath) {
        Set<String> declaredOptions =
                recipe.options().stream().map(OptionSpec::id).collect(java.util.stream.Collectors.toSet());
        for (FileRule rule : recipe.files()) {
            checkWhen(
                    rule.when(),
                    declaredOptions,
                    provided,
                    recipe,
                    displayPath,
                    "files[from=" + rule.from() + "].when");
        }
        int index = 0;
        for (PatchRule rule : recipe.patches()) {
            checkWhen(rule.when(), declaredOptions, provided, recipe, displayPath, "patches[" + index++ + "].when");
        }
    }

    private static void checkWhen(
            String expression,
            Set<String> declaredOptions,
            Set<Capability> provided,
            Recipe recipe,
            String displayPath,
            String field) {
        WhenExpression parsed;
        try {
            parsed = WhenExpression.parse(expression);
        } catch (IllegalArgumentException e) {
            throw new RecipeLoadException(displayPath, field, e.getMessage(), "See docs/recipe-format.md.", e);
        }
        for (String optionId : parsed.referencedOptions()) {
            if (!declaredOptions.contains(optionId)) {
                throw new RecipeLoadException(
                        displayPath,
                        field,
                        "reads option '" + optionId + "', which " + recipe.id() + " does not declare.",
                        declaredOptions.isEmpty()
                                ? "This recipe declares no options; add one under options[] or drop the condition."
                                : "Declare it under options[], or use one of: " + String.join(", ", declaredOptions));
            }
        }
        for (String capability : parsed.referencedCapabilities()) {
            if (!provided.contains(Capability.of(capability))) {
                throw new RecipeLoadException(
                        displayPath,
                        field,
                        "tests capability '" + capability + "', which no recipe in this catalog provides.",
                        "The condition can never be true. Fix the capability name, or add the recipe "
                                + "that provides it.");
            }
        }
    }

    private static void validateGlobs(LoadedRecipe entry, String displayPath) {
        for (FileRule rule : entry.recipe().files()) {
            if (matches(entry.directory(), rule.from()).isEmpty()) {
                throw new RecipeLoadException(
                        displayPath,
                        "files[from=" + rule.from() + "]",
                        "matches no file under " + entry.recipe().id() + "/.",
                        "Fix the glob or remove the entry. A rule that matches nothing is a file set "
                                + "somebody meant to ship and silently is not shipping.");
            }
        }
    }

    /** Files under {@code directory} matching a manifest glob, in stable path order. */
    public static List<Path> matches(Path directory, String glob) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(directory.relativize(path)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + directory, e);
        }
    }

    // --- digest --------------------------------------------------------------

    /**
     * sha256 over every byte of the recipe directory, paths included.
     *
     * <p>Paths are hashed as well as bodies so that renaming a template moves the hash: a rename
     * changes what the generated project looks like, and a digest that misses it would let a stale
     * cached zip stay servable.
     */
    static String contentHashOf(Path directory) {
        MessageDigest digest = Sha256.newDigest();
        try (Stream<Path> tree = Files.walk(directory)) {
            List<Path> files = tree.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path file : files) {
                digest.update(
                        directory.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not hash the recipe directory " + directory, e);
        }
        return Sha256.hex(digest.digest());
    }

    /** sha256 over the sorted set of (recipeId, version, contentHash) — §7. */
    static String digestOf(List<LoadedRecipe> loaded) {
        Map<String, String> lines = new LinkedHashMap<>();
        loaded.stream()
                .sorted(Comparator.comparing(entry -> entry.recipe().id()))
                .forEach(entry -> lines.put(
                        entry.recipe().id().value(), entry.recipe().version() + "\u0000" + entry.contentHash()));
        StringBuilder material = new StringBuilder();
        lines.forEach(
                (id, rest) -> material.append(id).append('\u0000').append(rest).append('\n'));
        return "sha256:" + Sha256.ofUtf8(material.toString());
    }
}
