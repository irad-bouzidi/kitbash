package dev.kitbash.core.recipe;

import java.util.Objects;

/**
 * A place in the catalog that a recipe can fill: {@code backend}, {@code database}, {@code docker}.
 *
 * <p>This is the piece the engine worked without and the wizard cannot. The resolver could infer
 * which option selected which recipe from the *value* — an option whose value names a recipe id
 * selects it — and that is enough to resolve a selection somebody has already made. It is not
 * enough to render one: a client needs the slot's id, label, help, type and position before
 * anything has been chosen, and no amount of looking at values will produce them.
 *
 * <p>So slots are declared, in {@code /recipes/_catalog.yaml}, and each recipe names the slot it
 * fills. §8 asks for exactly this — "option groups carry display order and labels, and every
 * human-readable string comes from the server" — and the side effect is that the resolver stops
 * inferring anything: an enum slot's value selects a recipe in that slot, a boolean slot's {@code
 * true} selects the recipes assigned to it, and everything else is configuration.
 */
public record Slot(
        String id, SlotType type, String label, String help, boolean required, boolean defaultOn, String group) {

    public Slot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        label = label == null || label.isBlank() ? id : label;
        if (help == null || help.isBlank()) {
            throw new IllegalArgumentException("slot '" + id + "' must carry help text");
        }
        if (required && type == SlotType.BOOLEAN) {
            throw new IllegalArgumentException(
                    "slot '" + id + "' is a toggle and cannot be required: `false` is an answer.");
        }
    }

    public boolean isEnum() {
        return type == SlotType.ENUM;
    }
}
