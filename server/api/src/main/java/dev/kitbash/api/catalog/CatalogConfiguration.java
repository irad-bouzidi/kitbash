package dev.kitbash.api.catalog;

import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.render.PebbleRenderStage;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public CatalogLoader.LoadedCatalog loadedCatalog() {
        Path root = locate();
        CatalogLoader.LoadedCatalog loaded = new CatalogLoader().loadAll(root);
        log.info(
                "Loaded {} recipes from {}, catalog digest {}",
                loaded.recipes().size(),
                root,
                loaded.catalog().digest());
        return loaded;
    }

    @Bean
    public Catalog catalog(CatalogLoader.LoadedCatalog loadedCatalog) {
        return loadedCatalog.catalog();
    }

    @Bean
    public GenerationPipeline generationPipeline(CatalogLoader.LoadedCatalog loadedCatalog) {
        return GenerationPipeline.over(loadedCatalog.catalog(), loadedCatalog.content(), new PebbleRenderStage());
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
