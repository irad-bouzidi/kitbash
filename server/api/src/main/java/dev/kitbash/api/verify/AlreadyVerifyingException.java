package dev.kitbash.api.verify;

import java.util.UUID;

/**
 * This caller already has a run in flight (§12, §13).
 *
 * <p>One per person, which is the whole throttle. A verification is minutes of a container, so a
 * caller who could start ten would hold the pool alone — and the limit that prevents it has to be
 * on concurrency rather than on request count, because the same person asking the same question
 * twice costs nothing at all (that is the dedupe) while asking two different ones costs double.
 *
 * <p>The id of the run they already have is carried back, because the useful answer to "you are
 * already verifying something" is which thing.
 */
public class AlreadyVerifyingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient UUID inFlight;

    public AlreadyVerifyingException(UUID inFlight) {
        super("This caller already has verification run " + inFlight + " in flight");
        this.inFlight = inFlight;
    }

    public UUID inFlight() {
        return inFlight;
    }
}
