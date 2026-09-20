package dev.kitbash.verify;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One combination, and the real commands that prove it works.
 *
 * <p>Data, not code, and deliberately so. {@code kitbash-35} enumerates cells to fill out the
 * matrix and {@code kitbash-37} builds one from a user's selection on demand; both of those are
 * new inputs to this type rather than new abstractions, which is only true while a cell stays
 * describable in a file.
 *
 * <p>§12's decisive point is in {@link Step#commands()}: a cell runs the stack's <b>real build and
 * test commands</b>, not a snapshot of rendered text. A generator whose output merely matches a
 * golden file is a generator that can emit projects nobody can compile.
 */
public record Cell(String id, String description, String selection, Set<String> triggers, List<Step> steps) {

    public Cell {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(selection, "selection");
        triggers = Set.copyOf(triggers);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(
                    "cell '" + id + "' runs no commands. A cell that builds nothing verifies nothing (§12).");
        }
    }

    public boolean runsOn(String trigger) {
        return triggers.contains(trigger);
    }

    /**
     * One ecosystem's worth of work: which image, where in the project, and what to run.
     *
     * <p>A full-stack project has two builds, so a cell has a list of these. Modelling it as one
     * step with a merged command list would mean one image containing both toolchains, and an
     * ecosystem image that accumulates tools stops resembling a user's machine.
     */
    public record Step(String ecosystem, String workingDirectory, List<String> commands) {

        public Step {
            Objects.requireNonNull(ecosystem, "ecosystem");
            workingDirectory = workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory;
            commands = List.copyOf(commands);
            if (commands.isEmpty()) {
                throw new IllegalArgumentException("a step must run at least one command");
            }
        }
    }
}
