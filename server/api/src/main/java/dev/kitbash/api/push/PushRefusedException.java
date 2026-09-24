package dev.kitbash.api.push;

/**
 * GitLab said no, in a way the user can act on (§14, §46).
 *
 * <p>A code and a hint rather than a status: §46 asks for the four failure modes that actually
 * occur to be told apart, and GitLab answers 400 for two of them and 404 for two more. What
 * distinguishes them is the body, and what a user needs is the next action.
 */
public class PushRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String code;
    private final transient String hint;

    public PushRefusedException(String code, String message, String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }

    public String code() {
        return code;
    }

    public String hint() {
        return hint;
    }
}
