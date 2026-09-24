package dev.kitbash.api.contributed;

import dev.kitbash.api.store.ContributedRecipe;
import dev.kitbash.api.store.ContributedRecipeRepository;
import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.catalog.SubmittedManifest;
import dev.kitbash.catalog.contributed.ContributedRecipeContent;
import dev.kitbash.catalog.contributed.DualSourceCatalog;
import dev.kitbash.core.plan.RecipeContent;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The catalog as it is right now, git half and contributed half (kitbash-48).
 *
 * <p>§47's "done when" requires an approved recipe to be generable, and kitbash-48 adds
 * <i>without restarting the server</i>. A {@link Catalog} is immutable and the application holds
 * one as a singleton, so something has to be allowed to change: this is that something, and it is
 * deliberately the only one.
 *
 * <p>Consumers do not ask this class for a catalog on every request. The pipeline holds a supplier
 * over {@link #catalog()} and reads it once per generation, so a swap between two requests is
 * invisible and a swap during one cannot happen. Two beans — {@code Catalog} and
 * {@code GenerationPipeline} — are built from it at boot and stay valid for the life of the
 * process.
 *
 * <h2>Rebuilt, not mutated</h2>
 *
 * <p>Every refresh composes a whole new {@link Catalog} from the unchanged git half and the
 * current contributed rows, and assigns it. Nothing is ever edited in place. That keeps the
 * immutability every other part of the system assumes, and it means a failed refresh leaves the
 * previous catalog serving rather than a half-updated one.
 */
public class LiveCatalog {

    private static final Logger log = LoggerFactory.getLogger(LiveCatalog.class);

    private final CatalogLoader.LoadedCatalog shipped;
    private final ContributedRecipeRepository contributed;

    private volatile Catalog catalog;
    private volatile RecipeContent content;

    public LiveCatalog(CatalogLoader.LoadedCatalog shipped, ContributedRecipeRepository contributed) {
        this.shipped = shipped;
        this.contributed = contributed;
        this.catalog = shipped.catalog();
        this.content = shipped.content();
    }

    public Catalog catalog() {
        return catalog;
    }

    public RecipeContent content() {
        return content;
    }

    /**
     * Recomposes from the approved rows.
     *
     * <p>Called after approval and after revocation, and from nowhere else. §47's rule is that a
     * contributed recipe is invisible until approved and gone once revoked, and those are exactly
     * the two moments visibility changes — a third caller would be a third opinion about when.
     */
    public synchronized void refresh() {
        List<ContributedRecipe> visible = contributed.visible();

        Map<Recipe, String> recipes = new LinkedHashMap<>();
        Map<RecipeId, Map<String, String>> files = new TreeMap<>();
        for (ContributedRecipe row : visible) {
            Recipe recipe = SubmittedManifest.parse(row.manifest(), row.recipeId() + "/recipe.yaml");
            recipes.put(recipe, row.contentHash());
            files.put(recipe.id(), filesOf(row));
        }

        Catalog composed = DualSourceCatalog.compose(shipped.catalog(), recipes);
        // Assigned together and catalog last, so a generation that reads the catalog can never
        // find content that does not match it. The reverse order would leave a window in which a
        // newly approved recipe is in the catalog and its files are not.
        this.content = ContributedRecipeContent.composed(shipped.content(), ContributedRecipeContent.of(files));
        this.catalog = composed;

        // §10's privacy rule: counts and a digest, no names beyond recipe ids.
        log.info("Catalog refreshed: {} contributed recipes visible, digest {}", visible.size(), composed.digest());
    }

    private static Map<String, String> filesOf(ContributedRecipe row) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(
                            row.content(),
                            new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String, String>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "Contributed recipe " + row.recipeId() + " holds content that will not parse", e);
        }
    }
}
