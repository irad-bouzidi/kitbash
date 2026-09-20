package dev.kitbash.verify;

import dev.kitbash.catalog.CatalogLoader;
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
 */
public final class MatrixMain {

    private MatrixMain() {}

    public static void main(String[] args) {
        Repository repository = Repository.locate();
        String only = flag(args, "--cell");
        String trigger = only != null ? "cell:" + only : flag(args, "--trigger", "merge-request");

        List<Cell> cells;
        try {
            cells = only != null
                    ? List.of(CellLoader.byId(repository.cells(), only))
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

        String digest = new CatalogLoader()
                .loadAll(repository.root().resolve("recipes"))
                .catalog()
                .digest();

        System.out.printf("kitbash verification — %s, %s, catalog %s%n%n", trigger, count(cells.size()), digest);

        CellResult.Matrix matrix = new MatrixRunner(
                        new CellRunner(repository, Containers.standard()), System.out::println)
                .run(trigger, digest, cells);

        StatusPage.write(matrix, repository);
        report(matrix, repository);

        System.exit(matrix.green() ? 0 : 1);
    }

    /**
     * The summary, with the failing cells' logs named.
     *
     * <p>§12 wants a failure to report the exact selection and a one-line local reproduction. Both
     * are already at the head of every cell's log, and repeating the reproduction here means the
     * person reading CI output does not have to open an artifact to get it.
     */
    private static void report(CellResult.Matrix matrix, Repository repository) {
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

        budget(matrix);

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
    private static void budget(CellResult.Matrix matrix) {
        long budget = Long.parseLong(System.getenv().getOrDefault("KITBASH_MATRIX_BUDGET", "600"));
        long elapsed = matrix.duration().toSeconds();
        System.out.println();
        System.out.printf("  wall clock:  %ds against a %ds budget%n", elapsed, budget);
        if (elapsed > budget) {
            System.out.printf(
                    "  OVER BUDGET: %ds > %ds — kitbash-35 multiplies this by the full matrix%n", elapsed, budget);
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
