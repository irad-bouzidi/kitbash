package dev.kitbash.api.push;

/**
 * A push that did not happen, with git's own words for why (§14, §46).
 *
 * <p>{@code detail} is redacted output kept for the log rather than the response: git's stderr is
 * where a group policy explains itself, and it is also where a remote URL carrying a token would
 * appear.
 */
public class PushFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String detail;

    public PushFailedException(String message, String detail) {
        super(message);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
