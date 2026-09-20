package dev.kitbash.render;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The five filters recipes may use, and no others.
 *
 * <p>Pebble ships a larger standard library, most of which is aimed at rendering HTML for humans.
 * What a code generator needs is name conversion, and keeping the set this small is what stops
 * template logic accreting: a recipe that needs something these cannot express is usually a recipe
 * that should be computing it in a hook (§4) instead.
 *
 * <p>Every filter added here must be documented in {@code docs/recipe-format.md} in the same change
 * — an undocumented filter is one only its author can use.
 */
final class KitbashFilters extends AbstractExtension {

    private final Map<String, Filter> filters = new LinkedHashMap<>();

    KitbashFilters() {
        add("packagePath", Naming::packagePath);
        add("camel", Naming::camel);
        add("pascal", Naming::pascal);
        add("kebab", Naming::kebab);
        add("snake", Naming::snake);
    }

    @Override
    public Map<String, Filter> getFilters() {
        return filters;
    }

    private void add(String name, UnaryOperator<String> conversion) {
        filters.put(name, new StringFilter(conversion));
    }

    /**
     * A filter over a string and nothing else. {@code null} in gives {@code null} out rather than an
     * empty string, so {@code strictVariables} stays the thing that catches a missing variable.
     */
    private record StringFilter(UnaryOperator<String> conversion) implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of();
        }

        @Override
        public Object apply(
                Object input,
                Map<String, Object> args,
                PebbleTemplate self,
                EvaluationContext context,
                int lineNumber) {
            return input == null ? null : conversion.apply(String.valueOf(input));
        }
    }
}
