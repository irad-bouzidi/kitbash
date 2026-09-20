package dev.kitbash.catalog;

import dev.kitbash.core.recipe.Recipe;
import java.nio.file.Path;

/**
 * A recipe together with where it came from and what it hashes to.
 *
 * <p>The directory is kept because the plan stage has to read the {@code files/} tree later, and
 * the content hash is kept because the catalog digest is computed over the whole set rather than
 * over manifests alone — a template body change has to move the digest, or a cached zip rendered
 * from the old body stays servable (§7, §10).
 */
public record LoadedRecipe(Recipe recipe, Path directory, String contentHash) {}
