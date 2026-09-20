package dev.kitbash.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.render.PebbleRenderStage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the repository's recipe tree and reference projects from a test's working directory.
 *
 * <p>The tests that use this run the generator exactly as the CLI and the matrix do — catalog,
 * render, core, no Spring — which is also the cheapest possible check that the §6 module boundary
 * still holds.
 */
final class ReferenceProjects {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReferenceProjects() {}

    /** The directory holding {@code /recipes}, walked up from wherever the test was started. */
    static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("recipes"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "Could not find the repository root from " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }

    static CatalogLoader.LoadedCatalog loadCatalog() {
        return new CatalogLoader().loadAll(repositoryRoot().resolve("recipes"));
    }

    static GenerationPipeline pipeline(CatalogLoader.LoadedCatalog loaded) {
        return GenerationPipeline.over(loaded.catalog(), loaded.content(), new PebbleRenderStage());
    }

    /** Every reference project directory, each of which carries its own variable set. */
    static List<Path> referenceProjects() {
        Path root = repositoryRoot().resolve("reference");
        try (var children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("reference-variables.json")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The checked-in selection this reference project is generated from (§4, kitbash-19). */
    static SelectionEnvelope selectionFor(Path referenceProject) {
        JsonNode document = read(referenceProject.resolve("reference-variables.json"));
        JsonNode selection = document.path("selection");

        Map<String, Object> options = new LinkedHashMap<>();
        selection
                .path("options")
                .propertyStream()
                .forEach(entry -> options.put(
                        entry.getKey(),
                        entry.getValue().isBoolean()
                                ? entry.getValue().asBoolean()
                                : entry.getValue().asText()));

        Map<String, String> variables = new LinkedHashMap<>();
        selection
                .path("variables")
                .propertyStream()
                .forEach(entry -> variables.put(entry.getKey(), entry.getValue().asText()));

        return new SelectionEnvelope(
                selection.path("schemaVersion").asInt(1),
                selection.path("projectName").asText(),
                options,
                variables);
    }

    /** Paths this reference project does not expect the generator to produce. */
    static List<String> excluded(Path referenceProject) {
        JsonNode document = read(referenceProject.resolve("reference-variables.json"));
        List<String> excluded = new java.util.ArrayList<>();
        document.path("excludeFromExtraction").forEach(node -> excluded.add(node.asText()));
        return List.copyOf(excluded);
    }

    private static JsonNode read(Path path) {
        try {
            return JSON.readTree(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
