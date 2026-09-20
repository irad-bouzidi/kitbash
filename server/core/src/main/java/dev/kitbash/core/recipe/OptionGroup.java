package dev.kitbash.core.recipe;

import java.util.List;
import java.util.Objects;

/**
 * A titled section of the wizard, and the order it appears in.
 *
 * <p>Grouping lives in the catalog rather than in the client because §8 is explicit that adding a
 * recipe is a backend-only change. A client that decided for itself which options belong together
 * would need a deploy every time the catalog grew an axis.
 */
public record OptionGroup(String id, String label, String help, int order, List<Slot> slots) {

    public OptionGroup {
        Objects.requireNonNull(id, "id");
        label = label == null || label.isBlank() ? id : label;
        slots = List.copyOf(slots);
    }
}
