package dev.kitbash.api.contributed;

/**
 * Two reviewers, one submission (§14, §47).
 *
 * <p>A 409 rather than a 400: nothing about the request was wrong, the world moved. The guards
 * live in the repository's where clauses, so this is what a failed conditional update turns into —
 * and the hint says to reload, because that is the only useful next action.
 */
public class ReviewConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String hint;

    public ReviewConflictException(String message, String hint) {
        super(message);
        this.hint = hint;
    }

    public String hint() {
        return hint;
    }
}
