package dev.kitbash.api.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.kitbash.api.generate.PopularSelections;
import dev.kitbash.api.store.GenerationRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stacks people actually build, in a form the nightly matrix can run (§10, §12, kitbash-40).
 *
 * <h2>What crosses, and what does not</h2>
 *
 * <p>§10 draws the line and this class is where it is drawn: <i>only hashes, recipe ids and
 * selections cross into the runner — never project or package names.</i> The selection is needed to
 * run the cell; the names are not.
 *
 * <p>So the variables are <b>replaced</b> rather than filtered. A deny-list of name-like keys would
 * be a list somebody has to remember to extend the next time a recipe declares a variable, and the
 * failure would be silent: a customer's package name in a CI log that nobody reads until they do.
 * What determines the <em>shape</em> of a generated project is its options; variables determine
 * what things are called, and a cell does not care what they are called.
 *
 * <p>The consequence is worth stating: a history cell's selection hashes differently from the
 * generation it came from. That is correct rather than unfortunate — the cell verifies the
 * combination, not the identity — and the original hash travels beside it as a label so the status
 * page can say which stack this is and how often it is built.
 */
public class HistoryCells {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The names every history cell is generated with.
     *
     * <p>The enumeration's, deliberately: a history cell and an enumerated cell that differ only in
     * their names would be two cells proving the same thing, and sharing the names means the
     * deduplication downstream compares options against options.
     */
    private static final Map<String, String> NEUTRAL_VARIABLES = Map.of(
            "groupId", "com.example",
            "packageName", "com.example.matrix",
            "javaVersion", "21",
            "entityName", "Widget",
            "entityTable", "widgets",
            "envPrefix", "MATRIX");

    /**
     * The one definition of "popular" (§40).
     *
     * <p>Through {@link PopularSelections} rather than straight to the repository, because §40 asks
     * for one definition and a second ranking query somewhere else is how two answers to "which
     * stacks matter" come to disagree.
     */
    private final PopularSelections popular;

    public HistoryCells(PopularSelections popular) {
        this.popular = popular;
    }

    /**
     * One stack somebody builds, stripped of who builds it.
     *
     * @param selectionHash the hash of the <em>original</em> selection, for labelling and counting
     *     — not of the envelope below, which has different names by construction
     * @param generations how many times it has been generated, so a red cell can be weighed
     * @param selection the envelope to run: the original options, neutral names
     */
    public record HistoryCell(String selectionHash, int generations, Map<String, Object> selection) {}

    public List<HistoryCell> top(int limit) {
        List<HistoryCell> cells = new ArrayList<>();
        for (GenerationRepository.PopularSelection selection : popular.top(limit)) {
            cells.add(new HistoryCell(
                    selection.selectionHash(), selection.generations(), anonymise(parse(selection.selection()))));
        }
        return List.copyOf(cells);
    }

    /**
     * The options, and nothing else that came from a person.
     *
     * <p>Built by construction rather than by removal: this reads the two keys it wants and writes
     * a new envelope, so a field added to the stored shape tomorrow does not arrive here by
     * default. §10's rule is worth enforcing structurally rather than by a list of exclusions.
     */
    static Map<String, Object> anonymise(JsonNode stored) {
        Map<String, Object> options = new LinkedHashMap<>();
        stored.path("options").properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isBoolean()) {
                options.put(entry.getKey(), value.asBoolean());
            } else if (!value.isNull() && !value.isMissingNode()) {
                options.put(entry.getKey(), value.asText());
            }
        });

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", stored.path("schemaVersion").asInt(1));
        // Not the stored one. A project name is the single field §10 names, and a cell has no use
        // for it beyond having one.
        envelope.put("projectName", "history-cell");
        envelope.put("options", options);
        envelope.put("variables", new LinkedHashMap<String, String>(NEUTRAL_VARIABLES));
        return envelope;
    }

    private static JsonNode parse(String selection) {
        try {
            return JSON.readTree(selection);
        } catch (IOException e) {
            throw new UncheckedIOException("A stored selection is not readable JSON", e);
        }
    }

    /** The envelope as JSON, which is what the runner is served. */
    public static ObjectNode toJson(HistoryCell cell) {
        return JSON.valueToTree(cell.selection());
    }
}
