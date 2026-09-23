package dev.kitbash.api.verify;

/**
 * No log for that cell in the run currently published (§14).
 *
 * <p>One answer for two situations, deliberately: a cell id that never existed and a cell whose log
 * has been cleaned away are the same thing to a caller — there is nothing to read — and
 * distinguishing them would tell an unauthenticated guess which ids are real.
 */
public class UnknownVerificationCellException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String cellId;

    public UnknownVerificationCellException(String cellId) {
        super("No published log for cell " + cellId);
        this.cellId = cellId;
    }

    public String cellId() {
        return cellId;
    }
}
