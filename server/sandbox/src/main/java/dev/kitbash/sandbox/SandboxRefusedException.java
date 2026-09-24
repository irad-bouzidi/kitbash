package dev.kitbash.sandbox;

/**
 * The render did not happen, and no contributed template was evaluated (§14, kitbash-47 §6.4).
 *
 * <p>Distinct from a render that failed: this is the fail-closed path. Either the host cannot
 * confine the worker, or the worker exceeded a cap and was killed. In both cases nothing
 * untrusted ran to completion, which is the fact the caller needs in order to decide whether to
 * retry, and the fact a generic exception would lose.
 */
public class SandboxRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String code;
    private final transient String hint;

    public SandboxRefusedException(String code, String message, String hint) {
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
