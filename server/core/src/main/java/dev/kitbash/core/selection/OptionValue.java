package dev.kitbash.core.selection;

import java.util.List;
import java.util.Objects;

/**
 * What a single option in the §7 envelope can hold.
 *
 * <p>The wire format is JSON, so a value arrives as a string, a boolean or an array; modelling that
 * as {@code Object} would push an {@code instanceof} chain into the resolver, the canonicaliser,
 * the template variable map and the metadata document. Sealing it instead means each of those
 * dispatches exhaustively, and a fourth value shape becomes a compile error rather than a default
 * branch nobody notices.
 */
public sealed interface OptionValue {

    /** The canonical JSON rendering of this value, which is hash input (§7). */
    String toJson();

    /** What a template sees. Primitives only — never a live object (§13). */
    Object templateValue();

    record Text(String value) implements OptionValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toJson() {
            return Json.quote(value);
        }

        @Override
        public Object templateValue() {
            return value;
        }
    }

    record Flag(boolean value) implements OptionValue {
        @Override
        public String toJson() {
            return Boolean.toString(value);
        }

        @Override
        public Object templateValue() {
            return value;
        }
    }

    /**
     * A multi-select, stored sorted and de-duplicated: two selections that differ only in the order
     * the user ticked the boxes are the same selection, and have to hash the same.
     */
    record Multi(List<String> values) implements OptionValue {
        public Multi {
            Objects.requireNonNull(values, "values");
            values = values.stream().distinct().sorted().toList();
        }

        @Override
        public String toJson() {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(Json.quote(values.get(i)));
            }
            return out.append(']').toString();
        }

        @Override
        public Object templateValue() {
            return values;
        }
    }

    static OptionValue text(String value) {
        return new Text(value);
    }

    static OptionValue flag(boolean value) {
        return new Flag(value);
    }

    static OptionValue multi(List<String> values) {
        return new Multi(values);
    }

    /**
     * Coerces a value decoded from JSON. A number is rendered through {@code toString} rather than
     * rejected, because {@code javaVersion: 21} is the mistake every client makes once and the
     * catalog type-checks the result against the option's declared type anyway.
     */
    static OptionValue fromJsonValue(Object value) {
        return switch (value) {
            case null -> new Text("");
            case OptionValue already -> already;
            case Boolean flag -> new Flag(flag);
            case String text -> new Text(text);
            case Number number -> new Text(number.toString());
            case List<?> list -> new Multi(list.stream().map(String::valueOf).toList());
            default ->
                throw new IllegalArgumentException(
                        "option values must be a string, a boolean or an array of strings (got: "
                                + value.getClass().getSimpleName() + ")");
        };
    }
}
