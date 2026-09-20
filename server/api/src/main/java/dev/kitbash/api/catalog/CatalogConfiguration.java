package dev.kitbash.api.catalog;

import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.catalog.LoadedRecipe;
import dev.kitbash.catalog.LoadedRecipeContent;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.render.PebbleRenderStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loads the recipe tree once, at boot, and holds it immutably for the life of the process (§10).
 *
 * <p>A malformed recipe therefore stops the application starting, which is the entire point of
 * keeping the catalog in git rather than in a database: the failure lands on whoever changed the
 * recipe, not on whoever downloads a broken project three weeks later.
 */
@Configuration
public class CatalogConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CatalogConfiguration.class);

    private final String configuredPath;

    public CatalogConfiguration(@Value("${kitbash.catalog.path:}") String configuredPath) {
        this.configuredPath = configuredPath;
    }

    @Bean
    public List<LoadedRecipe> loadedRecipes() {
        Path root = locate();
        List<LoadedRecipe> loaded = new CatalogLoader().loadDetailed(root);
        log.info("Loaded {} recipes from {}", loaded.size(), root);
        return loaded;
    }

    @Bean
    public Catalog catalog(List<LoadedRecipe> loadedRecipes) {
        return Catalog.of(
                loadedRecipes.stream().map(LoadedRecipe::recipe).toList(),
                CatalogLoader.digestOfRecipes(loadedRecipes));
    }

    @Bean
    public GenerationPipeline generationPipeline(Catalog catalog, List<LoadedRecipe> loadedRecipes) {
        return GenerationPipeline.over(catalog, LoadedRecipeContent.of(loadedRecipes), new PebbleRenderStage());
    }

    /**
     * {@code kitbash.catalog.path} when it is set, otherwise the nearest {@code recipes} directory
     * at or above the working directory.
     *
     * <p>Searching upward rather than hardcoding a relative path is what makes the same jar work
     * from the repository root, from {@code server/}, and from {@code /app} in the container — all
     * three of which really happen, in CI and in the verification runner. A wrong relative default
     * fails at the first request instead of at boot, which is the worse of the two failures.
     */
    private Path locate() {
        if (!configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath);
            if (!Files.isDirectory(configured)) {
                throw new IllegalStateException("kitbash.catalog.path points at " + configured.toAbsolutePath()
                        + ", which is not a directory.");
            }
            return configured;
        }
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path recipes = candidate.resolve("recipes");
            if (Files.isDirectory(recipes)) {
                return recipes;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("No recipe catalog found. Looked for a 'recipes' directory at or above "
                + Path.of("").toAbsolutePath() + ". Set kitbash.catalog.path to point at one.");
    }
}
