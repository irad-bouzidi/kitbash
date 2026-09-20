package dev.kitbash.core.recipe;

import java.util.Locale;
import java.util.Optional;

/**
 * How a slot is filled, and therefore what control the wizard renders for it.
 *
 * <p>Two, because two is what the catalog needs: a slot either holds one of several mutually
 * exclusive recipes, or it is a toggle for a single one. A third shape — several recipes at once —
 * would be a multi-select, and the day a feature axis needs one is the day to add it rather than
 * to guess at it now.
 */
public enum SlotType {
    ENUM,
    BOOLEAN;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SlotType> fromWireName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (SlotType type : values()) {
            if (type.wireName().equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
