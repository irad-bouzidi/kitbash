package dev.kitbash.core.recipe;

import dev.kitbash.core.selection.OptionValue;
import java.util.Map;
import java.util.Set;

/**
 * Everything a {@code when} expression is allowed to see: the effective option set and the
 * capabilities the resolved recipes provide.
 *
 * <p>Deliberately not the selection, the catalog or the filesystem. A manifest condition that could
 * reach any of those would turn {@code when} into a scripting language, and the §4 design depends
 * on recipes coordinating through capabilities rather than through knowledge of each other.
 */
public record WhenContext(Map<String, OptionValue> options, Set<Capability> capabilities) {

    public WhenContext {
        options = options == null ? Map.of() : Map.copyOf(options);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public static WhenContext of(Map<String, OptionValue> options, Set<Capability> capabilities) {
        return new WhenContext(options, capabilities);
    }

    /** The scalar form of an option, which is what {@code ==} compares against. */
    public String scalar(String optionId) {
        OptionValue value = options.get(optionId);
        return switch (value) {
            case null -> null;
            case OptionValue.Text text -> text.value();
            case OptionValue.Flag flag -> Boolean.toString(flag.value());
            case OptionValue.Multi ignored -> null;
        };
    }

    /**
     * Whether a bare option id reads as true. A boolean is itself; a string is true when it is set
     * to something other than the empty string or the literal {@code false}; a multi-select is true
     * when it is non-empty.
     */
    public boolean truthy(String optionId) {
        OptionValue value = options.get(optionId);
        return switch (value) {
            case null -> false;
            case OptionValue.Flag flag -> flag.value();
            case OptionValue.Text text -> !text.value().isBlank() && !"false".equals(text.value());
            case OptionValue.Multi multi -> !multi.values().isEmpty();
        };
    }
}
