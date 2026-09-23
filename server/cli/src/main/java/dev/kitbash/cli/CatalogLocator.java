package dev.kitbash.cli;

import dev.kitbash.catalog.CatalogLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds the recipe tree: the one named by {@code --catalog}, or the nearest {@code recipes}
 * directory at or above the working directory.
 *
 * <p>Searching upward is what makes {@code --catalog} optional inside the repository and what lets
 * the verification runner invoke this from wherever it happens to be. It is the same rule the API
 * uses, deliberately: two ways of finding the catalog would eventually find two different ones.
 */
final class CatalogLocator {

    private CatalogLocator() {}

    static CatalogLoader.LoadedCatalog load(Arguments arguments) {
        return new CatalogLoader().loadAll(locate(arguments));
    }

    static Path locate(Arguments arguments) {
        if (arguments.catalogPath().isPresent()) {
            return arguments.catalogPath().get();
        }
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path recipes = candidate.resolve("recipes");
            if (Files.isDirectory(recipes)) {
                return recipes;
            }
            candidate = candidate.getParent();
        }
        // A released binary ships its own recipes; inside the repository there are none beside
        // the launcher and the search above found the working tree's. Last rather than first, so a
        // developer running the CLI in the repository builds the catalog under review (§42).
        return Distribution.embeddedCatalog()
                .orElseThrow(() -> new IllegalStateException(
                        "No recipe catalog found. Looked for a 'recipes' directory at or above "
                                + Path.of("").toAbsolutePath()
                                + ", and beside the installed binary. Pass --catalog to name one."));
    }
}
