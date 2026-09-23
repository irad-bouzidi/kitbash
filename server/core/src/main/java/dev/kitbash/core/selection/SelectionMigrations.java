package dev.kitbash.core.selection;

import dev.kitbash.core.error.GenerationError;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Moves a stored selection forward, one schema version at a time.
 *
 * <p>There is exactly one version today and no migration to run, which is the entire reason to
 * write this now. A preset saved in phase 2 outlives every shape the envelope will ever have; once
 * rows exist, adding this becomes a data-migration problem with a backfill and a maintenance
 * window, whereas today it is a map with no entries in it.
 *
 * <p>Migrations are code in {@code core} (§7), not SQL, so they run identically for a preset read
 * from Postgres, a share link decoded from a URL and a selection posted by the CLI.
 */
public final class SelectionMigrations {

    public static final int CURRENT_VERSION = 1;

    /**
     * Version <i>n</i> → <i>n+1</i>. Add an entry, never edit an existing one: a migration that
     * changes behaviour after it has run is a migration that produces two different presents.
     */
    private static final Map<Integer, UnaryOperator<SelectionEnvelope>> MIGRATIONS = Map.of();

    private SelectionMigrations() {}

    public static SelectionEnvelope migrate(SelectionEnvelope envelope) {
        int version = envelope.schemaVersion();
        if (version < 1) {
            // Typed since kitbash-39: this is a parse-stage rejection of a selection, so it
            // carries the §14 envelope like every other one rather than a bare message with no
            // code for a client to switch on.
            throw GenerationError.invalidIdentifier(
                            "schemaVersion",
                            String.valueOf(version),
                            "must be at least 1",
                            "Omit the field entirely to mean the current version, which is what the " + "wizard sends.")
                    .asException();
        }
        if (version > CURRENT_VERSION) {
            throw GenerationError.invalidIdentifier(
                            "schemaVersion",
                            String.valueOf(version),
                            "is newer than this server understands (up to " + CURRENT_VERSION + ")",
                            "Upgrade the server, or regenerate the selection from the wizard — a "
                                    + "selection written by a newer Kitbash may name options this one has "
                                    + "never heard of.")
                    .asException();
        }
        SelectionEnvelope current = envelope;
        while (current.schemaVersion() < CURRENT_VERSION) {
            UnaryOperator<SelectionEnvelope> migration = MIGRATIONS.get(current.schemaVersion());
            if (migration == null) {
                throw new IllegalStateException("No migration registered from schemaVersion " + current.schemaVersion()
                        + " to " + (current.schemaVersion() + 1) + "; the chain to " + CURRENT_VERSION + " is broken.");
            }
            current = migration.apply(current).withSchemaVersion(current.schemaVersion() + 1);
        }
        return current;
    }

    /**
     * Renames an option key, the shape most migrations take. Exposed so a future migration is three
     * lines in the map above rather than a hand-rolled copy of this loop.
     */
    static UnaryOperator<SelectionEnvelope> renameOption(String from, String to) {
        return envelope -> {
            if (!envelope.options().containsKey(from)) {
                return envelope;
            }
            Map<String, Object> renamed = new LinkedHashMap<>();
            envelope.options().forEach((key, value) -> renamed.put(key.equals(from) ? to : key, value));
            return envelope.withOptions(renamed);
        };
    }
}
