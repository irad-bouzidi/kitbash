package dev.kitbash.core.recipe;

import java.util.Locale;
import java.util.Optional;

/**
 * What slot a recipe fills — and, because declaration order is the enum's natural order, the order
 * its files and patches are applied in.
 *
 * <p>§4 fixes the sequence: base → backend → frontend → features → infra → CI, ties broken by
 * recipe id. Encoding it as declaration order rather than as an {@code int} field means a new kind
 * cannot be added without deciding where in the pipeline it belongs.
 */
public enum RecipeKind {
    BASE,
    BACKEND,
    FRONTEND,
    MOBILE,
    FEATURE,
    INFRA,
    CI;

    /** The manifest spelling: lowercase, as it appears in {@code recipe.yaml}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<RecipeKind> fromWireName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (RecipeKind kind : values()) {
            if (kind.wireName().equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
