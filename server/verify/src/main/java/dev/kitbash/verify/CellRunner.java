package dev.kitbash.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one cell: generate, unpack, build in an ecosystem container.
 *
 * <p>The rule that holds from the first cell is §12's: a generated build never runs on the API
 * host. Everything after the unzip happens inside a disposable container with CPU and memory caps
 * and a hard timeout, and the project is mounted read-only and copied in, so a generated build
 * cannot write to the runner's filesystem.
 *
 * <p>Generation goes through {@code verification/generate.sh}, which calls the CLI. One
 * replaceable step with a fixed contract — selection in, zip out — is what kept switching from
 * HTTP to the CLI a one-file change, and it is what lets {@code kitbash-37} reuse this runner with
 * a selection that never came from a file.
 */
public final class CellRunner {

    private final Repository repository;
    private final Containers containers;

    public CellRunner(Repository repository, Containers containers) {
        this.repository = repository;
        this.containers = containers;
    }

    public CellResult run(Cell cell) {
        Instant started = Instant.now();
        Path log = repository.logFor(cell.id());
        String reproduce = "./verification/run-cell.sh " + cell.id();

        try (Workspace workspace = Workspace.create(cell.id())) {
            append(log, header(cell, reproduce), false);

            Path zip = workspace.path().resolve("project.zip");
            int generated = run(
                    List.of(
                            repository.generateScript().toString(),
                            repository.selectionFor(cell).toString(),
                            zip.toString()),
                    repository.root(),
                    log);
            if (generated != 0) {
                return CellResult.notGenerated(cell.id(), since(started), log, reproduce);
            }

            Path project = unpack(zip, workspace.path().resolve("unpacked"), log);
            String volume = cell.sharedWorkspace() ? createVolume(cell) : null;

            try {
                for (Cell.Step step : cell.steps()) {
                    append(
                            log,
                            "%n[cell:%s] %s in %s%n".formatted(cell.id(), step.ecosystem(), step.workingDirectory()),
                            true);
                    int exit = run(containers.commandFor(step, project, volume), repository.root(), log);
                    if (exit != 0) {
                        String failed = failedCommand(log, step, exit);
                        append(log, "%n[cell:%s] FAILED: %s%n".formatted(cell.id(), failed), true);
                        return CellResult.failed(cell.id(), since(started), log, failed, reproduce);
                    }
                }
            } finally {
                removeVolume(volume);
            }

            return CellResult.passed(cell.id(), since(started), log, reproduce);
        }
    }

    /**
     * A volume for one cell's steps to share, named after the cell and the run.
     *
     * <p>Named rather than anonymous so a failed run leaves something a person can inspect by name;
     * removed in a {@code finally} so a green matrix leaves nothing behind. If creation fails the
     * cell runs without one, which degrades to the default of a copy per step — a slower way to
     * reach the same answer, and not a reason to fail a cell.
     */
    private static String createVolume(Cell cell) {
        String name = "kitbash-cell-" + cell.id() + "-" + java.util.UUID.randomUUID();
        try {
            Process process = new ProcessBuilder("docker", "volume", "create", name)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0 ? name : null;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void removeVolume(String name) {
        if (name == null) {
            return;
        }
        try {
            new ProcessBuilder("docker", "volume", "rm", "-f", name)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Which of a step's commands broke.
     *
     * <p>The step ran as one script inside the container, so the answer comes back the only way it
     * can: the script printed it. A killed container prints nothing — {@code timeout} returns 124 —
     * so that case says so rather than blaming whichever command happened to be last.
     */
    private static String failedCommand(Path log, Cell.Step step, int exit) {
        if (exit == 124) {
            return "timed out: " + step.ecosystem() + " step in " + step.workingDirectory();
        }
        try {
            return Files.readAllLines(log).reversed().stream()
                    .filter(line -> line.startsWith(Containers.FAILED))
                    .map(line -> line.substring(Containers.FAILED.length()))
                    .findFirst()
                    .orElseGet(() -> step.ecosystem() + " step in " + step.workingDirectory());
        } catch (IOException e) {
            return step.ecosystem() + " step in " + step.workingDirectory();
        }
    }

    private Path unpack(Path zip, Path into, Path log) {
        append(log, "%n[unpack] %s%n".formatted(zip.getFileName()), true);
        int exit = run(List.of("unzip", "-q", zip.toString(), "-d", into.toString()), repository.root(), log);
        if (exit != 0) {
            throw new IllegalStateException("Could not unpack " + zip);
        }
        try (var children = Files.list(into)) {
            return children.filter(Files::isDirectory)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("The zip held no project directory"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Streams the process's combined output into the cell's log as it happens. */
    private static int run(List<String> command, Path workingDirectory, Path log) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
            if (!process.waitFor(2, TimeUnit.HOURS)) {
                process.destroyForcibly();
                return 124;
            }
            return process.exitValue();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + String.join(" ", command), e);
        }
    }

    /**
     * The header every log opens with.
     *
     * <p>§12 requires the failure output to carry the exact selection JSON and a one-line local
     * reproduction. Putting both at the top of the log rather than only in the summary means the
     * artifact somebody downloads three days later is still self-contained.
     */
    private String header(Cell cell, String reproduce) {
        List<String> lines = new ArrayList<>();
        lines.add("cell:        " + cell.id());
        if (cell.description() != null) {
            lines.add("description: " + cell.description());
        }
        lines.add("reproduce:   " + reproduce);
        lines.add("");
        lines.add("selection:");
        try {
            Files.readAllLines(repository.selectionFor(cell)).forEach(line -> lines.add("    " + line));
        } catch (IOException e) {
            lines.add("    (unreadable: " + e.getMessage() + ")");
        }
        lines.add("");
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static void append(Path log, String text, boolean exists) {
        try {
            Files.createDirectories(log.getParent());
            if (exists) {
                Files.writeString(
                        log,
                        text,
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(log, text, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + log, e);
        }
    }

    private static Duration since(Instant started) {
        return Duration.between(started, Instant.now());
    }

    /** A temporary directory that removes itself, so a failed cell leaves nothing behind. */
    private record Workspace(Path path) implements AutoCloseable {

        static Workspace create(String cellId) {
            try {
                return new Workspace(Files.createTempDirectory("kitbash-cell-" + cellId + "-"));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create a workspace for " + cellId, e);
            }
        }

        @Override
        public void close() {
            try (var walk = Files.walk(path)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException ignored) {
                        // A build may leave a file the runner cannot remove; the tmpdir is
                        // disposable either way and failing here would hide the real result.
                    }
                });
            } catch (IOException ignored) {
                // Same reasoning.
            }
        }
    }
}
