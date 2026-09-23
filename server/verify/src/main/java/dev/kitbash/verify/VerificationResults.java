package dev.kitbash.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The matrix, written in the shape the wizard's badges need (§12, kitbash-38).
 *
 * <p>{@link StatusPage} answers "what happened in this run?" for a person reading CI output. This
 * answers a different question — "is <em>this combination</em> known to build?" — and it needs one
 * thing the status page does not carry: the options each cell was generated from. A badge decorates
 * an option pairing, so the selection behind a cell is the whole point rather than a detail.
 *
 * <p>Written per run, and merged across shards by whoever collects them. A shard knows only its own
 * twelfth of the matrix, so a document from one shard is true and incomplete; merging is addition,
 * because two shards never contain the same cell.
 *
 * <h2>Why a file and not a row</h2>
 *
 * <p>§12 keeps verification independent of the API, its auth and its persistence, so a red cell
 * means the generator is broken rather than the deployment. The runner therefore has no database
 * and writes a document; the API reads it. Giving the runner a JDBC connection so it could write
 * {@code verification_run} rows directly would buy the dedupe a warm start and cost the property
 * that makes a red cell diagnosable.
 */
public final class VerificationResults {

    /** Bumped when a reader would have to change. */
    public static final int SCHEMA_VERSION = 1;

    public static final String FILE_NAME = "verification.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private VerificationResults() {}

    /** Writes {@code verification/build/verification.json} for the run that just finished. */
    public static void write(CellResult.Matrix matrix, Repository repository) {
        write(matrix, repository, Clock.systemUTC());
    }

    static void write(CellResult.Matrix matrix, Repository repository, Clock clock) {
        Path file = repository.output().resolve(FILE_NAME);
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document(matrix, repository, clock));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    static ObjectNode document(CellResult.Matrix matrix, Repository repository, Clock clock) {
        ObjectNode document = JSON.createObjectNode();
        document.put("schemaVersion", SCHEMA_VERSION);
        document.put("catalogDigest", matrix.catalogDigest());
        document.put("trigger", matrix.trigger());
        document.put("generatedAt", clock.instant().toString());

        ArrayNode cells = document.putArray("cells");
        for (CellResult result : matrix.results()) {
            ObjectNode cell = cells.addObject();
            cell.put("id", result.cellId());
            cell.put("outcome", result.outcome().name());
            if (result.failedStep() != null) {
                cell.put("failedStep", result.failedStep());
            }
            cell.put("reproduce", result.reproduce());
            ObjectNode options = cell.putObject("options");
            optionsOf(repository, result.cellId(), matrix).forEach((key, value) -> {
                if (value instanceof Boolean flag) {
                    options.put(key, flag);
                } else {
                    options.put(key, String.valueOf(value));
                }
            });
        }
        return document;
    }

    /**
     * The options a cell was generated from, read back from its selection file.
     *
     * <p>Read rather than carried on {@link Cell}, because a cell's selection is a file by design —
     * checked in for the seventeen, written by the enumeration for the rest — and a second copy on
     * the record would be a second thing that could disagree with {@code generate.sh}'s input.
     *
     * <p>A selection that cannot be read yields no options. The cell still appears, with its
     * outcome, because a run that happened is worth reporting even when nothing can be said about
     * which pairings it covers.
     */
    private static Map<String, Object> optionsOf(Repository repository, String cellId, CellResult.Matrix matrix) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (Path candidate : selectionCandidates(repository, cellId)) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(candidate.toFile()).path("options");
                node.properties().forEach(entry -> {
                    JsonNode value = entry.getValue();
                    if (value.isBoolean()) {
                        options.put(entry.getKey(), value.asBoolean());
                    } else if (!value.isNull() && !value.isMissingNode()) {
                        options.put(entry.getKey(), value.asText());
                    }
                });
                return options;
            } catch (IOException unreadable) {
                return options;
            }
        }
        return options;
    }

    /**
     * Where a cell's selection might be, in the order the runner would have found it.
     *
     * <p>Three kinds of cell, three homes: the checked-in ones name their own path inside
     * {@code cells/*.json}, the enumeration writes under {@code build/enumerated/}, and a requested
     * cell under {@code build/requested/}. Looking in all three means this works for whichever
     * trigger produced the run rather than only for the nightly.
     */
    private static List<Path> selectionCandidates(Repository repository, String cellId) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(repository.output().resolve("enumerated").resolve(cellId + ".json"));
        candidates.add(repository.output().resolve("requested").resolve(cellId + ".json"));
        Path checkedIn = repository.cells().resolve(cellId + ".json");
        if (Files.isRegularFile(checkedIn)) {
            try {
                String selection =
                        JSON.readTree(checkedIn.toFile()).path("selection").asText(null);
                if (selection != null) {
                    candidates.add(repository.verification().resolve(selection));
                }
            } catch (IOException unreadable) {
                // Falls through to whatever the other candidates find, which is nothing.
            }
        }
        return candidates;
    }
}
