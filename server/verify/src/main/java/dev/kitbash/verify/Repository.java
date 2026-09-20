package dev.kitbash.verify;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where everything the runner needs lives, found once from the working directory.
 *
 * <p>Searching upward for the {@code recipes} directory is the same rule the API and the CLI
 * follow. Three ways of finding the repository would eventually find three different ones, and the
 * symptom would be a matrix verifying a catalog nobody deployed.
 */
public record Repository(Path root) {

    public static Repository locate() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("recipes"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "No repository root found at or above " + Path.of("").toAbsolutePath());
        }
        return new Repository(candidate);
    }

    public Path verification() {
        return root.resolve("verification");
    }

    public Path cells() {
        return verification().resolve("cells");
    }

    public Path generateScript() {
        return verification().resolve("generate.sh");
    }

    /** Build output, not source: logs and the status page are artifacts of a run. */
    public Path output() {
        return verification().resolve("build");
    }

    public Path logFor(String cellId) {
        return output().resolve("logs").resolve(cellId + ".log");
    }

    public Path selectionFor(Cell cell) {
        return verification().resolve(cell.selection());
    }
}
