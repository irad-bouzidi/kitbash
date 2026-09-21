package dev.kitbash.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads {@code /verification/cells/*.json}.
 *
 * <p>Cells are files rather than a list in code so that adding one is a reviewable diff a
 * maintainer can write without touching the runner — and so that {@code kitbash-35} can generate
 * ninety of them without generating Java.
 */
public final class CellLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CellLoader() {}

    public static List<Cell> load(Path cellsDirectory) {
        if (!Files.isDirectory(cellsDirectory)) {
            throw new IllegalArgumentException("No cell definitions at " + cellsDirectory.toAbsolutePath());
        }
        try (Stream<Path> files = Files.list(cellsDirectory)) {
            List<Cell> cells = new ArrayList<>();
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> cells.add(read(path)));
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("No cells in " + cellsDirectory.toAbsolutePath()
                        + ". A matrix with nothing in it is green for the wrong reason.");
            }
            return List.copyOf(cells);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + cellsDirectory, e);
        }
    }

    /** The cells a trigger runs, in id order so a run reads the same way twice. */
    public static List<Cell> forTrigger(Path cellsDirectory, String trigger) {
        return load(cellsDirectory).stream()
                .filter(cell -> cell.runsOn(trigger))
                .sorted(Comparator.comparing(Cell::id))
                .toList();
    }

    /**
     * One cell by id, whatever it is tagged with.
     *
     * <p>An unknown id lists the known ones: the caller is usually a person retyping what a red
     * pipeline printed, and "no such cell" without the alternatives sends them to find this
     * directory by hand.
     */
    public static Cell byId(Path cellsDirectory, String id) {
        List<Cell> cells = load(cellsDirectory);
        return cells.stream()
                .filter(cell -> cell.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No cell named '" + id + "'. There is "
                        + String.join(
                                ", ", cells.stream().map(Cell::id).sorted().toList()) + "."));
    }

    static Cell read(Path file) {
        JsonNode node;
        try {
            node = JSON.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the cell at " + file, e);
        }

        Set<String> triggers = new LinkedHashSet<>();
        node.path("triggers").forEach(trigger -> triggers.add(trigger.asText()));

        List<Cell.Step> steps = new ArrayList<>();
        for (JsonNode step : node.path("steps")) {
            List<String> commands = new ArrayList<>();
            step.path("commands").forEach(command -> commands.add(command.asText()));
            steps.add(new Cell.Step(
                    step.path("ecosystem").asText(),
                    step.path("workingDirectory").asText("."),
                    commands));
        }

        try {
            return new Cell(
                    node.path("id").asText(),
                    node.path("description").asText(null),
                    node.path("selection").asText(),
                    triggers,
                    node.path("sharedWorkspace").asBoolean(false),
                    steps);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }
}
