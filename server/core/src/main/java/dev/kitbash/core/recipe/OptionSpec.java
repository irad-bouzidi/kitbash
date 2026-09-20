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
 *
 * <p>{@code demands} is how a toggle asks for a capability without its recipe having to require
 * one unconditionally. The frontend recipe is the case that forced it: §18 settles that a
 * standalone frontend is supported, so {@code requires: [rest-api]} would make the configuration
 * the plan explicitly allows impossible to express — while the typed-client option genuinely does
 * need a backend that publishes an OpenAPI document. Null for every option that demands nothing.
 */
public record OptionSpec(
        String id,
        OptionType type,
        List<String> values,
        OptionValue defaultValue,
        String label,
        String help,
        Capability demands) {

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
        if (demands != null && type != OptionType.BOOLEAN) {
            throw new IllegalArgumentException("option '" + id
                    + "' declares `demands`, which only means something for a boolean: a toggle that is on "
                    + "requires a capability, and an enum value cannot say which of its values does.");
        }
    }

    /** The five-argument form, for the common option that demands nothing. */
    public static OptionSpec of(
            String id, OptionType type, List<String> values, OptionValue defaultValue, String label, String help) {
        return new OptionSpec(id, type, values, defaultValue, label, help, null);
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
