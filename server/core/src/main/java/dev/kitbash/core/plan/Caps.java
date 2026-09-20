package dev.kitbash.core.plan;

import dev.kitbash.core.error.GenerationError;
import java.time.Duration;

/**
 * The §13 resource limits, checked at the plan stage.
 *
 * <p>The plan stage is where they belong because it is the last moment before cost is incurred: the
 * shape of the project is fully known — how many files, whose they are, how large the sources are —
 * and nothing has been rendered, patched or streamed. A cap enforced after rendering has already
 * paid for the work it was supposed to prevent, and one enforced while streaming means a truncated
 * zip the client cannot tell from a network failure.
 *
 * <p>The numbers are §13's, and {@code LIMIT_EXCEEDED} names the cap and the observed value so a
 * user is told which knob they tripped rather than that something was too big.
 */
public record Caps(int maxFiles, long maxTotalBytes, long maxFileBytes, Duration wallClock) {

    public static Caps standard() {
        return new Caps(5_000, 50L * 1024 * 1024, 5L * 1024 * 1024, Duration.ofSeconds(10));
    }

    /** For tests that need to trip a cap without building a 50 MB fixture. */
    public static Caps of(int maxFiles, long maxTotalBytes, long maxFileBytes) {
        return new Caps(maxFiles, maxTotalBytes, maxFileBytes, Duration.ofSeconds(10));
    }

    /**
     * The wall-clock cap, as something a stage can carry between checks.
     *
     * <p>§13 caps generation at ten seconds. That one cannot be enforced by looking at the plan,
     * because it is not a property of the selection — it is a property of the run, and the run is
     * what might be pathological: a template with a runaway loop, a recipe tree on a filesystem
     * that stalls. So the deadline is started when the request begins and checked at the stage
     * boundaries, which are the points where a partially-finished project can still be abandoned
     * cheaply.
     */
    public Deadline deadline() {
        return new Deadline(System.nanoTime() + wallClock.toNanos(), wallClock);
    }

    /** A started clock. Checked between stages; never mid-file, where the answer would not help. */
    public record Deadline(long expiresAtNanos, Duration budget) {

        public void check(String stage) {
            long overrunNanos = System.nanoTime() - expiresAtNanos;
            if (overrunNanos > 0) {
                throw GenerationError.limitExceeded(
                                "wall clock (exceeded during " + stage + ")",
                                budget.toMillis(),
                                budget.toMillis()
                                        + Duration.ofNanos(overrunNanos).toMillis())
                        .asException();
            }
        }
    }

    void checkFileCount(int observed) {
        if (observed > maxFiles) {
            throw GenerationError.limitExceeded("file count", maxFiles, observed)
                    .asException();
        }
    }

    void checkTotalBytes(long observed) {
        if (observed > maxTotalBytes) {
            throw GenerationError.limitExceeded("uncompressed size", maxTotalBytes, observed)
                    .asException();
        }
    }

    void checkFileBytes(String path, long observed) {
        if (observed > maxFileBytes) {
            throw GenerationError.limitExceeded("single file size (" + path + ")", maxFileBytes, observed)
                    .asException();
        }
    }
}
