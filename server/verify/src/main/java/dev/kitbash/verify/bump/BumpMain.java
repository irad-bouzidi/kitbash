package dev.kitbash.verify.bump;

import dev.kitbash.verify.Repository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code ./gradlew :verify:bumpVersions} — the weekly job's first half.
 *
 * <p>It rewrites the manifests and writes a summary the pull request body is made of. It does not
 * open anything: deciding whether the matrix is green, and what to do about it, belongs to the
 * workflow, and a job that both changed the catalog and judged itself would be one thing too many.
 *
 * <p>Exit codes are the channel: {@code 0} something moved, {@code 3} nothing to do. Not {@code 1},
 * because "the catalog is already current" is the answer on most Mondays and a red job every Monday
 * is a job nobody reads.
 */
public final class BumpMain {

    private BumpMain() {}

    public static void main(String[] args) {
        Repository repository = Repository.locate();
        Bumper bumper = new Bumper(repository.root().resolve("recipes"), new MavenCentral());

        List<String> problems = new ArrayList<>();
        Bumper.Result result = bumper.plan(problems);

        problems.forEach(problem -> System.out.println("  could not check " + problem));

        if (result.isEmpty()) {
            System.out.println("Every tracked version is already the latest release.");
            write(repository, List.of(), problems, result.majors());
            System.exit(3);
        }

        List<Bump> applied = new ArrayList<>();
        for (Map.Entry<TrackedVersion, String> entry : result.available().entrySet()) {
            int files = bumper.apply(entry.getKey(), entry.getValue());
            if (files == 0) {
                // The manifest says it pins a literal that is nowhere in the recipe. That is a
                // manifest drifting from what it describes, and it is worth saying out loud.
                problems.add(entry.getKey().artifact() + ": '" + entry.getKey().version()
                        + "' is declared in the manifest and appears in no file");
                continue;
            }
            applied.add(new Bump(entry.getKey(), entry.getValue(), files));
        }

        applied.forEach(bump -> System.out.println("  " + bump.describe()));
        write(repository, applied, problems, result.majors());
        System.exit(applied.isEmpty() ? 3 : 0);
    }

    /** A file, because the workflow needs the same text in a pull request body or an issue. */
    private static void write(
            Repository repository, List<Bump> applied, List<String> problems, Map<TrackedVersion, String> majors) {
        StringBuilder summary = new StringBuilder();
        if (applied.isEmpty()) {
            summary.append("No version moved.\n");
        } else {
            summary.append("| Recipe | Dependency | From | To |\n| --- | --- | --- | --- |\n");
            for (Bump bump : applied) {
                summary.append("| `%s` | %s | `%s` | `%s` |%n"
                        .formatted(
                                bump.tracked().recipe(),
                                bump.tracked().describe(),
                                bump.tracked().version(),
                                bump.to()));
            }
        }
        if (!majors.isEmpty()) {
            // Offered, not taken. A major upgrade moves packages and changes defaults, and a job
            // that proposed one every week would be red every week — which is the same as off.
            summary.append("\n**A major version is available, and this job does not take them**\n\n");
            majors.forEach((tracked, version) -> summary.append("- %s in `%s`: `%s` → `%s`%n"
                    .formatted(tracked.describe(), tracked.recipe(), tracked.version(), version)));
        }

        if (!problems.isEmpty()) {
            summary.append("\n**Not checked**\n\n");
            problems.forEach(problem -> summary.append("- ").append(problem).append('\n'));
        }

        Path file = repository.output().resolve("bump-summary.md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, summary.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
        System.out.println();
        System.out.println("summary: " + repository.root().relativize(file));
    }
}
