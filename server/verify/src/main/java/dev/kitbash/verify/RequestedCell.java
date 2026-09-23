package dev.kitbash.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A cell for a selection nobody enumerated — {@code kitbash-37}'s half of the runner.
 *
 * <p>§12 puts on-demand verification and the nightly matrix on one runner deliberately, and this is
 * the join: a requested selection becomes an ordinary {@link Cell}, runs through the same
 * {@link CellRunner}, in the same images, under the same §13 limits. Nothing about a user's
 * combination gets a softer treatment than a combination the catalog happened to enumerate.
 *
 * <p>The selection is written to a file because that is the contract {@code generate.sh} has —
 * selection in, zip out — and keeping it means the CLI, the matrix and this share one generation
 * path. Under {@code verification/build}, which is output rather than source: a run's selection is
 * an artifact of the run, like its log.
 */
public final class RequestedCell {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RequestedCell() {}

    /**
     * One cell, from the selection envelope a user sent.
     *
     * @param id what the run is called — the verification run's id, so a log found on disk can be
     *     traced back to the row that asked for it
     * @param envelope the wire-shape selection: {@code schemaVersion}, {@code projectName},
     *     {@code options}, {@code variables}
     */
    public static Cell from(Repository repository, String id, JsonNode envelope) {
        Path file = repository.output().resolve("requested").resolve(id + ".json");
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), envelope);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the requested selection " + id, e);
        }

        Map<String, Object> options = optionsOf(envelope);
        return new Cell(
                id,
                "Requested: " + options,
                // Relative to `verification/`, because that is what Repository.selectionFor
                // resolves against — the same rule the checked-in and enumerated cells follow.
                repository.verification().relativize(file).toString(),
                // No trigger. A requested cell is run by id and belongs to no schedule; giving it
                // one would put a user's selection into the nightly, which nobody asked for.
                java.util.Set.of(),
                CellSteps.sharedWorkspace(options),
                CellSteps.forOptions(options));
    }

    /**
     * The options, as the step derivation wants them.
     *
     * <p>Booleans stay booleans and everything else becomes its text. {@link CellSteps} asks
     * {@code equals} questions of both kinds, and a {@code BooleanNode} answers none of them.
     */
    private static Map<String, Object> optionsOf(JsonNode envelope) {
        Map<String, Object> options = new LinkedHashMap<>();
        envelope.path("options").properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isBoolean()) {
                options.put(entry.getKey(), value.asBoolean());
            } else if (!value.isNull() && !value.isMissingNode()) {
                options.put(entry.getKey(), value.asText());
            }
        });
        return options;
    }
}
