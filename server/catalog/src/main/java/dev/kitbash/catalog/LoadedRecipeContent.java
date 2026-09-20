package dev.kitbash.catalog;

import dev.kitbash.core.plan.RecipeContent;
import dev.kitbash.core.recipe.RecipeId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plan stage's view of a loaded recipe tree.
 *
 * <p>{@code core} defines {@link RecipeContent} so the planner can be tested without a filesystem;
 * this is the implementation that reads the directories {@link CatalogLoader} validated at boot.
 *
 * <p>Bytes are read lazily, through the supplier on each {@code RecipeFile}. The plan stage
 * deliberately knows sizes without materialising content so it can enforce the §13 caps before any
 * cost is paid (§6), and eagerly slurping every template here would defeat that for no gain.
 */
public final class LoadedRecipeContent implements RecipeContent {

    private final Map<RecipeId, Path> directories;

    private LoadedRecipeContent(Map<RecipeId, Path> directories) {
        this.directories = Map.copyOf(directories);
    }

    public static LoadedRecipeContent of(List<LoadedRecipe> loaded) {
        Map<RecipeId, Path> directories = new LinkedHashMap<>();
        loaded.forEach(entry -> directories.put(entry.recipe().id(), entry.directory()));
        return new LoadedRecipeContent(directories);
    }

    @Override
    public List<RecipeFile> filesMatching(RecipeId recipeId, String glob) {
        Path directory = directories.get(recipeId);
        if (directory == null) {
            throw new IllegalStateException("No directory is loaded for recipe '" + recipeId + "'. The catalog and the "
                    + "resolution have come from different loads.");
        }
        return CatalogLoader.matches(directory, glob).stream()
                .map(path -> toRecipeFile(directory, path))
                .toList();
    }

    private static RecipeFile toRecipeFile(Path directory, Path path) {
        try {
            String relative = directory.relativize(path).toString().replace('\\', '/');
            return new RecipeFile(relative, () -> read(path), Files.isExecutable(path), Files.size(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stat " + path, e);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
