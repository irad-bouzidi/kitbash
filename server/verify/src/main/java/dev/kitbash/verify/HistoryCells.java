package dev.kitbash.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.core.recipe.Catalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The cells that come from what people actually build (§12, §40).
 *
 * <p>§12 calls this <i>the piece neither plan had</i>. An enumerated matrix tests the catalog's
 * cross-product, which is not the same set as the stacks a team relies on: a combination that is
 * unusual on paper but is one team's house standard deserves nightly coverage more than a cell
 * nobody has ever generated.
 *
 * <h2>Optional by construction</h2>
 *
 * <p>The list is fetched once, before the run, and a failure to fetch it is not a failure of the
 * nightly. §12's rule that verification stays independent of the API is about <em>running</em> a
 * cell — nothing here touches a database and generation still goes through the CLI — but a nightly
 * that fell over because the API was down would be a nightly that stops reporting that the catalog
 * is broken, which is the one thing it exists to do.
 *
 * <p>So: no {@code KITBASH_HISTORY_URL}, no history cells, and the report says so.
 */
public final class HistoryCells {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HistoryCells() {}

    /**
     * One stack somebody builds, as the runner sees it.
     *
     * @param generations how often it has been built — printed beside the cell, so a red one can be
     *     weighed against how many people it affects
     */
    public record HistoryCell(String selectionHash, int generations, JsonNode selection) {

        /** Short and traceable: the hash is what the API counted, so it is what the id carries. */
        public String id() {
            // Measured against the *stripped* hash, not the original — taking twelve characters of
            // something after removing seven from it is how a short hash threw.
            String digest = selectionHash.replace("sha256:", "");
            return "h-" + digest.substring(0, Math.min(12, digest.length()));
        }
    }

    /**
     * What was found, what could not be, and everything that was weighed.
     *
     * @param considered every popular stack the API offered, including the ones deduplicated away
     *     — the weekly report is about the <em>set</em>, and a cell that stopped being offered is
     *     the drift worth noticing
     */
    public record Result(List<Cell> cells, List<String> notes, List<HistoryCell> considered) {}

    /**
     * Fetches the popular stacks and turns the ones worth running into cells.
     *
     * @param enumerated the cells the catalog already produces, so history adds rather than repeats
     */
    public static Result fetch(Repository repository, Catalog catalog, List<Cell> enumerated) {
        String url = System.getenv("KITBASH_HISTORY_URL");
        if (url == null || url.isBlank()) {
            return new Result(
                    List.of(),
                    List.of("No KITBASH_HISTORY_URL: the nightly ran the enumerated matrix only. "
                            + "History-fed cells (§40) need an API to ask."),
                    List.of());
        }

        List<HistoryCell> popular;
        try {
            popular = request(url);
        } catch (IOException | InterruptedException unreachable) {
            Thread.currentThread().interrupt();
            return new Result(
                    List.of(),
                    List.of("Could not read history cells from " + url + " (" + unreachable.getMessage()
                            + "); ran the enumerated matrix only."),
                    List.of());
        }

        return select(repository, catalog, enumerated, popular);
    }

    /**
     * Which popular stacks become cells.
     *
     * <p>Two exclusions, both from §40 and both reported rather than silent. A stack the
     * enumeration already covers is not worth a second container — the interesting history cells
     * are the few the cross-product misses. And a stack naming a recipe this catalog no longer has
     * is a <b>catalog change, not a regression</b>: reporting it as a failure would make every
     * removed recipe look like a broken nightly.
     */
    static Result select(Repository repository, Catalog catalog, List<Cell> enumerated, List<HistoryCell> popular) {
        Set<String> alreadyCovered = new LinkedHashSet<>();
        for (Cell cell : enumerated) {
            alreadyCovered.add(shapeOf(repository.selectionFor(cell)));
        }

        List<Cell> cells = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> known = catalog.recipes().stream()
                .map(recipe -> recipe.id().value())
                .collect(java.util.stream.Collectors.toSet());

        for (HistoryCell candidate : popular) {
            List<String> gone = missingRecipes(candidate.selection(), known);
            if (!gone.isEmpty()) {
                notes.add("%s (%d generations) names %s, which this catalog no longer has — skipped, "
                                .formatted(candidate.id(), candidate.generations(), String.join(", ", gone))
                        + "because a removed recipe is a catalog change rather than a regression.");
                continue;
            }
            if (alreadyCovered.contains(shapeOf(candidate.selection()))) {
                continue;
            }
            cells.add(RequestedCell.from(repository, candidate.id(), candidate.selection()));
        }
        return new Result(List.copyOf(cells), List.copyOf(notes), List.copyOf(popular));
    }

    /**
     * What makes two cells the same cell: their options, sorted.
     *
     * <p>Not the selection hash. A history cell's names were replaced before it left the API, so
     * its hash can never equal an enumerated cell's — comparing hashes would deduplicate nothing
     * and the nightly would rebuild every popular stack it already covers.
     */
    private static String shapeOf(JsonNode envelope) {
        List<String> options = new ArrayList<>();
        envelope.path("options").properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isBoolean() && !value.asBoolean()) {
                return;
            }
            options.add(entry.getKey() + "=" + (value.isBoolean() ? "true" : value.asText()));
        });
        java.util.Collections.sort(options);
        return String.join(",", options);
    }

    private static String shapeOf(java.nio.file.Path selectionFile) {
        try {
            return shapeOf(JSON.readTree(selectionFile.toFile()));
        } catch (IOException unreadable) {
            // A cell whose selection cannot be read deduplicates against nothing, which at worst
            // runs a history cell twice.
            return "";
        }
    }

    private static List<String> missingRecipes(JsonNode envelope, Set<String> known) {
        List<String> gone = new ArrayList<>();
        envelope.path("options").properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual() && value.asText().contains("-") && !known.contains(value.asText())) {
                gone.add(value.asText());
            }
        });
        return gone;
    }

    private static List<HistoryCell> request(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        String token = System.getenv("KITBASH_HISTORY_TOKEN");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            List<HistoryCell> cells = new ArrayList<>();
            for (JsonNode node : JSON.readTree(response.body())) {
                cells.add(new HistoryCell(
                        node.path("selectionHash").asText(),
                        node.path("generations").asInt(),
                        node.path("selection")));
            }
            return List.copyOf(cells);
        }
    }

    /**
     * The history set, written down so the next run can say what changed (§40).
     *
     * <p>§40 asks for a report of which history cells were added or dropped, <i>so the set does not
     * drift silently</i>. The drift is the thing to catch: a stack falling out of the top twenty
     * because usage moved is information, and a stack falling out because a recipe was renamed and
     * nobody noticed is a coverage hole that looks identical from inside one run.
     *
     * <p>A run cannot compare itself to the last one, so it writes what it used; comparing is the
     * workflow's job, against the previous run's artifact. One file, one line per cell, sorted —
     * so a diff is a diff rather than a reordering.
     */
    public static void publish(Repository repository, List<Cell> historyCells, List<HistoryCell> considered) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (HistoryCell candidate : considered) {
            counts.put(candidate.id(), candidate.generations());
        }
        Set<String> ran = new LinkedHashSet<>();
        historyCells.forEach(cell -> ran.add(cell.id()));

        StringBuilder document = new StringBuilder();
        counts.forEach((id, generations) ->
                document.append("%s\t%d\t%s%n".formatted(id, generations, ran.contains(id) ? "ran" : "skipped")));

        java.nio.file.Path file = repository.output().resolve("history-cells.tsv");
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, document.toString());
        } catch (IOException e) {
            // Losing the record costs the next run its diff, not this run its result.
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
