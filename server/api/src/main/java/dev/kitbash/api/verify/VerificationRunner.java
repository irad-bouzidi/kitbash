package dev.kitbash.api.verify;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * What actually builds a requested combination (§12).
 *
 * <p>A seam for the same reason {@code ZipCache} is one: the thing behind it takes minutes and a
 * Docker daemon, and the questions worth testing about {@link VerificationService} — who gets a
 * container, who gets somebody else's answer, what happens when the queue is full — are all
 * answerable without one. What a container does to a generated project is proved by the matrix,
 * ninety-eight cells at a time, and proving it again per unit test would be slower and no truer.
 */
public interface VerificationRunner {

    /** What a run produced: the verdict, the log, and which step broke if one did. */
    record Outcome(boolean passed, String log, String failedStep) {}

    /**
     * Builds the selection and answers by {@code deadline}.
     *
     * @param runId the verification run's id, which is also the cell's id, so a log found on disk
     *     traces back to the row that asked for it
     */
    Outcome run(String runId, JsonNode envelope, Instant deadline);
}
