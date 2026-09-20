package dev.kitbash.api.store;

/**
 * Who can see a preset (§10).
 *
 * <p>A typed enum over a {@code text} column, rather than a Postgres enum type: adding a value to a
 * database enum is a migration with a lock, and the vocabulary belongs in the code that reasons
 * about it. The wire name is what the column holds, so a row is readable in psql.
 */
public enum Visibility {
    PRIVATE,
    TEAM,
    PUBLIC;

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Visibility of(String wireName) {
        return valueOf(wireName.toUpperCase(java.util.Locale.ROOT));
    }
}
