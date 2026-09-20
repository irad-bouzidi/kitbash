package dev.kitbash.core.recipe;

import dev.kitbash.core.selection.OptionValue;
import java.util.List;
import java.util.Objects;

/**
 * One knob a recipe exposes, described completely enough that a client which has never heard of it
 * can render a control, a label and a help string (§8, §9).
 *
 * <p>{@code help} is not optional. An option whose meaning is not written down here becomes an
 * option somebody explains once in a wiki page nobody reads.
 */
public record OptionSpec(
        String id, OptionType type, List<String> values, OptionValue defaultValue, String label, String help) {

    public OptionSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        values = values == null ? List.of() : List.copyOf(values);
        label = label == null || label.isBlank() ? id : label;
        if (help == null || help.isBlank()) {
            throw new IllegalArgumentException("option '" + id + "' must carry help text");
        }
        if ((type == OptionType.ENUM || type == OptionType.MULTI_SELECT) && values.isEmpty()) {
            throw new IllegalArgumentException(
                    "option '" + id + "' is a " + type.wireName() + " and must declare values");
        }
    }

    /** Whether this option can legally hold {@code value}: shape and domain checked in one place. */
    public boolean accepts(OptionValue value) {
        return switch (value) {
            case OptionValue.Flag ignored -> type == OptionType.BOOLEAN;
            case OptionValue.Text text ->
                switch (type) {
                    case STRING -> true;
                    case ENUM -> values.contains(text.value());
                    case BOOLEAN, MULTI_SELECT -> false;
                };
            case OptionValue.Multi multi -> type == OptionType.MULTI_SELECT && values.containsAll(multi.values());
        };
    }
}
