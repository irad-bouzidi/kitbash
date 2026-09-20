package dev.kitbash.api.catalog;

import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * Puts the catalog digest and recipe count on {@code /actuator/info} (§8).
 *
 * <p>That digest is what makes a bug report actionable: "generation produced the wrong thing" is
 * unanswerable, and "generation produced the wrong thing, catalog sha256:9f2c…" identifies the
 * exact recipe set down to the byte. It is the same value the web footer shows (§9) and the same
 * one every generation's lock records (§7).
 */
@Component
public class CatalogInfoContributor implements InfoContributor {

    private final Catalog catalog;

    public CatalogInfoContributor(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("digest", catalog.digest());
        details.put("recipes", catalog.size());
        details.put(
                "recipeIds",
                catalog.recipes().stream().map(recipe -> recipe.id().value()).toList());
        details.put("slots", catalog.slotIds());
        details.put(
                "frameworkVersions",
                catalog.recipes().stream()
                        .filter(recipe -> recipe.frameworkVersion() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                recipe -> recipe.id().value(),
                                Recipe::frameworkVersion,
                                (first, second) -> first,
                                LinkedHashMap::new)));
        builder.withDetail("catalog", details);
    }
}
