package dev.kitbash.core.selection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The §7 selection envelope exactly as it arrives: possibly at an older schema version, with option
 * values still in whatever shape JSON decoding produced.
 *
 * <p>It is a separate type from {@link Selection} because they answer different questions. This one
 * is what a stored preset or a five-month-old share link holds; {@code Selection} is what the
 * resolver runs on. Keeping them apart is what makes {@link SelectionMigrations} possible at all —
 * a single type would have to be simultaneously valid at every version it has ever had.
 */
public record SelectionEnvelope(
        int schemaVersion, String projectName, Map<String, Object> options, Map<String, String> variables) {

    public static final int CURRENT_SCHEMA_VERSION = SelectionMigrations.CURRENT_VERSION;

    public SelectionEnvelope {
        Objects.requireNonNull(projectName, "projectName");
        options = options == null ? Map.of() : Map.copyOf(options);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static SelectionEnvelope current(
            String projectName, Map<String, Object> options, Map<String, String> variables) {
        return new SelectionEnvelope(CURRENT_SCHEMA_VERSION, projectName, options, variables);
    }

    /** Stage 1 of §6: migrate to the current schema, then type every option value. */
    public Selection parse() {
        SelectionEnvelope migrated = SelectionMigrations.migrate(this);
        Map<String, OptionValue> typed = new LinkedHashMap<>();
        migrated.options.forEach((key, value) -> typed.put(key, OptionValue.fromJsonValue(value)));
        return new Selection(migrated.projectName, typed, migrated.variables);
    }

    public SelectionEnvelope withSchemaVersion(int version) {
        return new SelectionEnvelope(version, projectName, options, variables);
    }

    public SelectionEnvelope withOptions(Map<String, Object> newOptions) {
        return new SelectionEnvelope(schemaVersion, projectName, newOptions, variables);
    }
}
