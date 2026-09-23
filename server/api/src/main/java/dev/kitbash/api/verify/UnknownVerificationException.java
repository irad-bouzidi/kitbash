package dev.kitbash.api.verify;

import java.util.UUID;

/**
 * No run by that id (§14).
 *
 * <p>A 404 rather than a 410, because a run id is a uuid a client either has or invented: there is
 * no expiry that turns a known id into a gone one. The row outlives its log by design — after
 * thirty days the verdict is still there and only the detail is missing — so "the log expired" is
 * a different answer, given by a 200 with no log rather than by this.
 */
public class UnknownVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient UUID id;

    public UnknownVerificationException(UUID id) {
        super("No verification run " + id);
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
