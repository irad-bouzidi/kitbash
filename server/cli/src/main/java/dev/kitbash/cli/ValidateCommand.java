package dev.kitbash.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.selection.SelectionValidationException;
import dev.kitbash.render.PebbleRenderStage;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code kitbash validate} — stages 1–2 and nothing else.
 *
 * <p>Exits non-zero when the selection cannot be generated from, so a script can gate on it, and
 * prints the resolved stack either way: the useful answer to "will this work?" includes what it
 * would have produced.
 */
final class ValidateCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    int run(Arguments arguments, PrintStream out, PrintStream err) {
        try {
            CatalogLoader.LoadedCatalog catalog = CatalogLocator.load(arguments);
            Resolution resolution = dev.kitbash.core.pipeline.GenerationPipeline.over(
                            catalog.catalog(), catalog.content(), new PebbleRenderStage())
                    .validate(Selections.read(arguments.selection()));

            out.println(JSON.valueToTree(describe(resolution, catalog.catalog().digest()))
                    .toPrettyString());

            if (resolution.valid()) {
                return 0;
            }
            GenerationError first = resolution.firstConflict();
            return first == null ? 1 : ErrorOutput.print(first, err);
        } catch (SelectionValidationException | IllegalArgumentException | IllegalStateException e) {
            return ErrorOutput.print(e.getMessage(), err);
        } catch (IOException e) {
            return ErrorOutput.print("Could not read the selection: " + e.getMessage(), err);
        }
    }

    private static Map<String, Object> describe(Resolution resolution, String catalogDigest) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("valid", resolution.valid());
        document.put("catalogDigest", catalogDigest);

        List<Map<String, Object>> recipes = new ArrayList<>();
        for (Recipe recipe : resolution.recipes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", recipe.id().value());
            entry.put("label", recipe.label());
            entry.put("recipeVersion", recipe.version().toString());
            entry.put("implied", resolution.implied().contains(recipe.id()));
            recipes.add(entry);
        }
        document.put("recipes", recipes);
        document.put(
                "capabilities",
                resolution.capabilities().stream()
                        .map(Capability::name)
                        .sorted()
                        .toList());

        Map<String, Object> options = new LinkedHashMap<>();
        resolution.effectiveOptions().forEach((id, value) -> options.put(id, value.templateValue()));
        document.put("effectiveOptions", options);

        document.put(
                "conflicts",
                resolution.conflicts().stream()
                        .map(error -> Map.of(
                                "error", error.code().name(),
                                "message", error.message(),
                                "hint", error.hint()))
                        .toList());
        document.put(
                "warnings",
                resolution.warnings().stream()
                        .map(warning -> Map.of("message", warning.message(), "hint", warning.hint()))
                        .toList());
        return document;
    }
}
