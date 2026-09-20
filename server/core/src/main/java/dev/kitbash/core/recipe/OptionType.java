package dev.kitbash.core.recipe;

import java.util.Locale;
import java.util.Optional;

/**
 * The closed set of option shapes a client may encounter.
 *
 * <p>§9 requires the wizard's only switch to be on option *type*, never on option id. That is only
 * possible if this set is small, closed and served in the metadata document — so a manifest cannot
 * invent a type, and adding one is a deliberate change on both sides of the API.
 */
public enum OptionType {
    ENUM,
    BOOLEAN,
    STRING,
    MULTI_SELECT;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<OptionType> fromWireName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (OptionType type : values()) {
            if (type.wireName().equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
