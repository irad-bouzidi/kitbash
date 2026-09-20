package dev.kitbash.api.store;

/**
 * How a generation ended (§10).
 *
 * <p>A failed generation is still a row: §14's errors are things users ask about, and a history
 * that only remembers successes cannot answer "what went wrong last Tuesday".
 */
public enum GenerationStatus {
    SUCCEEDED,
    FAILED;

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static GenerationStatus of(String wireName) {
        return valueOf(wireName.toUpperCase(java.util.Locale.ROOT));
    }
}
