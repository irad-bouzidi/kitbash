package dev.kitbash.core.plan;

import dev.kitbash.core.recipe.RecipeId;
import java.util.List;
import java.util.function.Supplier;

/**
 * Where a recipe's {@code files/} tree comes from, as seen by the plan stage.
 *
 * <p>An interface so that {@code core} can build a plan without knowing whether the bytes are on
 * disk, on a classpath or in a test's map. That keeps the §6 module boundary honest — {@code
 * catalog} owns the recipe tree, {@code core} owns the algorithm — and it is what lets the planner's
 * tests run without a filesystem.
 */
public interface RecipeContent {

    /**
     * Files under {@code recipeId}'s directory matching a manifest glob, in stable path order, with
     * paths relative to the recipe directory (so {@code files/src/Main.java.peb}, not the project
     * path it will end up at — stripping the glob's prefix is the planner's rule to apply, in one
     * place).
     */
    List<RecipeFile> filesMatching(RecipeId recipeId, String glob);

    /** One file inside a recipe directory. */
    record RecipeFile(String path, Supplier<byte[]> content, boolean executable, long size) {}
}
