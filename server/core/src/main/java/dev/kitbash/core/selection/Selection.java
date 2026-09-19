package dev.kitbash.core.selection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The §7 selection envelope: what the caller asked for.
 *
 * <p>Flat and keyed by option id, never nested by category — a nested shape bakes the category
 * taxonomy into every client, and adding a category then becomes a breaking change.
 *
 * <p>Phase 0 honours only {@code projectName} and the four variables below; the rest is carried but
 * unused. It is modelled fully anyway so no client has to change shape when the resolver arrives.
 * Unknown option keys are accepted and ignored **for this phase only** — once the catalog exists,
 * an unknown option id is a rejected request.
 */
public record Selection(
        int schemaVersion, String projectName, Map<String, Object> options, Map<String, String> variables) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public Selection {
        Objects.requireNonNull(projectName, "projectName");
        options = options == null ? Map.of() : Map.copyOf(options);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static Selection of(String projectName, Map<String, String> variables) {
        return new Selection(CURRENT_SCHEMA_VERSION, projectName, Map.of(), variables);
    }

    public String variable(String name, String fallback) {
        String value = variables.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * The canonical form the cache key is computed over: keys sorted, blank values elided. Not used
     * for caching yet — the cache is kitbash-27 — but the canonicalisation belongs with the type it
     * canonicalises rather than with the first caller that needs it.
     */
    public Selection canonical() {
        Map<String, Object> canonicalOptions = new LinkedHashMap<>(new java.util.TreeMap<>(options));
        Map<String, String> canonicalVariables = new LinkedHashMap<>();
        new java.util.TreeMap<>(variables).forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                canonicalVariables.put(key, value);
            }
        });
        return new Selection(schemaVersion, projectName, canonicalOptions, canonicalVariables);
    }
}
