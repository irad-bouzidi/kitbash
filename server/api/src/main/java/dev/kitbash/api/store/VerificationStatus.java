package dev.kitbash.api.store;

/**
 * Where a verification run got to (§10).
 *
 * <p>{@link #FAILED} is deliberately outside the dedupe index: a failed run is a result somebody
 * may want to reproduce after fixing the recipe, so it must not block a retry. The other three do
 * make a second run redundant, which is exactly what the partial index encodes.
 */
public enum VerificationStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED;

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static VerificationStatus of(String wireName) {
        return valueOf(wireName.toUpperCase(java.util.Locale.ROOT));
    }

    /** The three the dedupe index covers. */
    public boolean blocksAnotherRun() {
        return this != FAILED;
    }
}
