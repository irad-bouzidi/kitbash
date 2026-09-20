package dev.kitbash.api.store;

/**
 * Whether a preset follows the catalog or holds still (§10).
 *
 * <p>{@code PINNED} is the reason {@code pinned_recipes} exists: a preset that pins is a preset
 * that has to remember what it pinned to, because the catalog will have moved on.
 */
public enum VersionPolicy {
    TRACK_LATEST,
    PINNED;

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static VersionPolicy of(String wireName) {
        return valueOf(wireName.toUpperCase(java.util.Locale.ROOT));
    }
}
