package dev.kitbash.verify;

import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.recipe.Catalog;
import java.util.List;

/**
 * The entry point CI calls: {@code --trigger merge-request} or {@code --trigger nightly}.
 *
 * <p>Nothing here decides which cells to run. The trigger names a tag, the cell files carry the
 * tags, and adding a cell to a trigger is a one-line edit to a JSON file — which is what keeps
 * {@code kitbash-35}'s ninety cells from becoming ninety lines of Java.
 *
 * <p>{@code --cell <id>} runs exactly one, whatever its tags. That is the mode
 * {@code verification/run-cell.sh} uses, so reproducing a red cell runs the same code CI ran
 * rather than a shell script's approximation of it.
 *
 * <p>{@code --enumerate} replaces the checked-in cells with the full matrix derived from the
 * catalog (§35), and {@code --shard k/n} runs one deterministic slice of whatever was selected.
 * Sharding is by cell rather than by ecosystem, which §35 suggested: ecosystems are not the same
 * size, and putting every JVM cell in one shard parallelises almost nothing. Sorted ids split
 * round-robin means shard 3 of 6 contains the same cells on every run, so a flaky cell is
 * reproducible by shard as well as by id.
 */
public final class MatrixMain {

    private MatrixMain() {}

    public static void main(String[] args) {
        Repository repository = Repository.locate();
        String only = flag(args, "--cell");
        boolean enumerate = List.of(args).contains("--enumerate");
        String trigger = only != null ? "cell:" + only : flag(args, "--trigger", "merge-request");
        Catalog catalog = new CatalogLoader()
                .loadAll(repository.root().resolve("recipes"))
                .catalog();

        List<Cell> cells;
        try {
            cells = only != null
                    ? List.of(oneCell(repository, catalog, only))
                    : enumerate
                            ? Enumeration.cells(catalog, repository)
                            : CellLoader.forTrigger(repository.cells(), trigger);
        } catch (IllegalArgumentException e) {
            // A mistyped cell id is a person retyping what a red pipeline printed. They get the
            // sentence, which names the real ids, rather than a stack trace through Optional.
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        if (cells.isEmpty()) {
            System.err.println("No cells are tagged '" + trigger + "'. A matrix with nothing in it is green "
                    + "for the wrong reason.");
            System.exit(2);
        }

        String shard = flag(args, "--shard");
        int total = cells.size();
        if (shard != null) {
            cells = shardOf(cells, shard);
            trigger = trigger + " shard " + shard;
        }

        String digest = catalog.digest();

        System.out.printf(
                "kitbash verification — %s, %s%s, catalog %s%n%n",
                trigger, count(cells.size()), shard == null ? "" : " of " + total, digest);

        CellResult.Matrix matrix = new MatrixRunner(
                        new CellRunner(repository, Containers.standard()), System.out::println)
                .run(trigger, digest, cells);

        StatusPage.write(matrix, repository);
        report(matrix, repository, enumerate);

        System.exit(matrix.green() ? 0 : 1);
    }

    /**
     * One cell by id, from the checked-in cells first and the enumeration second.
     *
     * <p>The matrix prints {@code run-cell.sh <id>} beside every failure, and most of the ids it
     * can print belong to cells §35 derives from the catalog rather than to files in
     * {@code verification/cells/}. Looking in both means the reproduction line the runner offers
     * is one that actually runs — which was not true of the ninety-six enumerated cells, whose
     * printed command answered "no cell named that" and listed the seventeen it was not.
     */
    static Cell oneCell(Repository repository, Catalog catalog, String id) {
        try {
            return CellLoader.byId(repository.cells(), id);
        } catch (IllegalArgumentException notCheckedIn) {
            return Enumeration.cells(catalog, repository).stream()
                    .filter(cell -> cell.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            notCheckedIn.getMessage() + " No cell derived from the catalog is named that either."));
        }
    }

    /**
     * One slice of the matrix, chosen so the same cell is always in the same shard.
     *
     * <p>Round-robin over the id-sorted list rather than contiguous blocks: contiguous blocks put
     * every Maven cell together, and a shard's duration would then depend on where the alphabet
     * happened to land. Interleaving mixes fast and slow cells into every shard on its own.
     */
    static List<Cell> shardOf(List<Cell> cells, String shard) {
        String[] parts = shard.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("--shard wants k/n, for example 2/6; got '" + shard + "'");
        }
        int index = Integer.parseInt(parts[0]);
        int count = Integer.parseInt(parts[1]);
        if (count < 1 || index < 1 || index > count) {
            throw new IllegalArgumentException("--shard " + shard + " is not a slice of anything");
        }

        List<Cell> sorted =
                cells.stream().sorted(java.util.Comparator.comparing(Cell::id)).toList();
        List<Cell> slice = new java.util.ArrayList<>();
        for (int i = index - 1; i < sorted.size(); i += count) {
            slice.add(sorted.get(i));
        }
        return List.copyOf(slice);
    }

    /**
     * The summary, with the failing cells' logs named.
     *
     * <p>§12 wants a failure to report the exact selection and a one-line local reproduction. Both
     * are already at the head of every cell's log, and repeating the reproduction here means the
     * person reading CI output does not have to open an artifact to get it.
     */
    private static void report(CellResult.Matrix matrix, Repository repository, boolean enumerate) {
        System.out.println();
        System.out.println("================================================================");
        System.out.printf(
                " %s — %s in %ds%n",
                matrix.green() ? "MATRIX GREEN" : "MATRIX RED",
                count(matrix.results().size()),
                matrix.duration().toSeconds());
        System.out.println("================================================================");

        for (CellResult result : matrix.results()) {
            System.out.printf(
                    "  %-8s %-24s %4ds%n",
                    result.outcome(), result.cellId(), result.duration().toSeconds());
        }

        budget(matrix, enumerate);

        if (!matrix.green()) {
            System.out.println();
            for (CellResult result : matrix.results()) {
                if (result.passed()) {
                    continue;
                }
                System.out.printf("  %s failed at: %s%n", result.cellId(), result.failedStep());
                System.out.printf("    log:       %s%n", repository.root().relativize(result.log()));
                System.out.printf("    reproduce: %s%n", result.reproduce());
            }
        }

        System.out.println();
        System.out.printf(
                "  status page: %s%n",
                repository.root().relativize(repository.output().resolve("status.html")));
    }

    /**
     * The wall clock, against §12's ten minutes for a merge-request run.
     *
     * <p>Printed every run rather than only when breached, so the trend is visible before it is a
     * problem — {@code kitbash-35} multiplies this number by the size of the full matrix.
     *
     * <p>It does not turn the matrix red. A red cell should mean the generated project is broken,
     * not that the runner had a slow afternoon, and a CI job timeout already catches the runaway
     * case. The number being loud is what makes it actionable.
     */
    private static void budget(CellResult.Matrix matrix, boolean enumerate) {
        // §12 sets two: ten minutes for a merge request and twenty for the nightly, both warm.
        // With sharding this is one shard's wall clock, which is the number that matters — the
        // shards run in parallel, so the job is as slow as its slowest one.
        String fallback = enumerate ? "1200" : "600";
        long budget = Long.parseLong(System.getenv().getOrDefault("KITBASH_MATRIX_BUDGET", fallback));
        long elapsed = matrix.duration().toSeconds();
        System.out.println();
        System.out.printf("  wall clock:  %ds against a %ds budget%n", elapsed, budget);
        if (elapsed > budget) {
            System.out.printf(
                    "  OVER BUDGET: %ds > %ds — shard further, or make the slowest cells faster%n", elapsed, budget);
        }
    }

    /** One cell is a cell, not 1 cells — the single-cell form is the one a person reads most. */
    static String count(int cells) {
        return cells == 1 ? "1 cell" : cells + " cells";
    }

    private static String flag(String[] args, String name, String fallback) {
        String value = flag(args, name);
        return value == null ? fallback : value;
    }

    private static String flag(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name) && !args[i + 1].isBlank()) {
                return args[i + 1];
            }
        }
        return null;
    }
}
