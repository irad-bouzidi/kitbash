package dev.kitbash.core.recipe;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a variable's value belongs in the §7 selection envelope.
 *
 * <p>Two, because the envelope has two places: {@code projectName} is a field of its own and
 * everything else lives in {@code variables}. Declaring which is which means a client renders both
 * the same way and assembles them correctly without naming either — the alternative is one {@code
 * if (id === 'projectName')} in the wizard, which is the special case §9 says not to write.
 */
public enum VariableScope {
    /** A top-level field of the envelope. Today that is only {@code projectName}. */
    ENVELOPE,
    /** An entry in the envelope's {@code variables} map. */
    VARIABLE;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<VariableScope> fromWireName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (VariableScope scope : values()) {
            if (scope.wireName().equals(value)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
