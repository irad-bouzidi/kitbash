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

    /**
     * The catalog as it is now.
     *
     * <p>Since kitbash-48 this is a view over {@link dev.kitbash.api.contributed.LiveCatalog} when
     * there is one — approving a contributed recipe has to take effect without a restart, and this
     * bean is injected in a handful of places that must not go stale. Without persistence there
     * are no contributed recipes and it is the git catalog, unchanged.
     *
     * <p>{@code ObjectProvider} rather than an optional dependency, because the holder lives
     * behind the {@code persistence} profile and this configuration does not.
     */
    @Bean
    public Catalog catalog(
            CatalogLoader.LoadedCatalog loadedCatalog,
            org.springframework.beans.factory.ObjectProvider<dev.kitbash.api.contributed.LiveCatalog> live) {
        return live.getIfAvailable() == null
                ? loadedCatalog.catalog()
                : live.getObject().catalog();
    }

    /**
     * The pipeline, over suppliers rather than values (kitbash-48).
     *
     * <p>One bean for the life of the process that nevertheless sees an approval, because it asks
     * the holder each time rather than holding a catalog. The alternative was to make nine
     * controllers fetch a fresh pipeline, which is the same indirection spread over nine places to
     * forget it.
     *
     * <p>The render stage is routed by provenance when contributed recipes are possible: shipped
     * templates in process, contributed ones in the sandbox. Without persistence there can be no
     * contributed recipe, so the plain stage is correct and costs nothing.
     */
    @Bean
    public GenerationPipeline generationPipeline(
            CatalogLoader.LoadedCatalog loadedCatalog,
            org.springframework.beans.factory.ObjectProvider<dev.kitbash.api.contributed.LiveCatalog> live,
            org.springframework.beans.factory.ObjectProvider<dev.kitbash.sandbox.SandboxedRenderer> sandbox) {
        dev.kitbash.api.contributed.LiveCatalog holder = live.getIfAvailable();
        dev.kitbash.core.pipeline.RenderStage stage = sandbox.getIfAvailable() == null
                ? new PebbleRenderStage()
                : new dev.kitbash.sandbox.ContributedRenderStage(sandbox.getObject());

        return holder == null
                ? GenerationPipeline.over(loadedCatalog.catalog(), loadedCatalog.content(), stage)
                : GenerationPipeline.over(holder::catalog, holder::content, stage);
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
