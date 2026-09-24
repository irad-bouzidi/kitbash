package dev.kitbash.render;

import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.selection.OptionValue;
import dev.kitbash.core.selection.Selection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a template can see, and nothing else.
 *
 * <p>§13: <i>the variable map holds validated primitives only, never live service objects.</i> That
 * is enforced here rather than trusted: {@link Builder#put} rejects anything that is not a string,
 * a boolean, a number or a list of strings, so a future caller cannot pass the catalog, a
 * repository or a Spring bean into a template and quietly turn the sandbox into an object graph.
 *
 * <p>Together with the deny-all method validator in {@link TemplateEngine}, this is the whole of
 * the sandbox's data side: a template that cannot call a method and can only see primitives has
 * nothing to reach through.
 */
public final class TemplateVariables {

    private final Map<String, Object> values;

    private TemplateVariables(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The standard variable map: the caller's declared variables, the resolved option set, and
     * {@code projectName}.
     *
     * <p>Options are laid down first so a variable of the same name wins. The two namespaces are
     * flat in the envelope (§7) and collisions are rare, but a silent one would mean a template
     * reading the wrong value — so the precedence is fixed here, once, rather than per template.
     */
    /**
     * The same map plus the facts a recipe knows about itself.
     *
     * <p>A README fragment that says "Spring Boot 3.5.5" should read the number out of the manifest
     * that already holds it, not carry a second copy destined to drift from the first.
     */
    public TemplateVariables forRecipe(Recipe recipe) {
        Builder builder = builder();
        values.forEach(builder::put);
        builder.put("recipeId", recipe.id().value());
        builder.put("recipeVersion", recipe.version().toString());
        builder.put("frameworkVersion", recipe.frameworkVersion() == null ? "" : recipe.frameworkVersion());
        return builder.build();
    }

    public static TemplateVariables of(Selection selection, Map<String, OptionValue> effectiveOptions) {
        return of(selection, effectiveOptions, java.util.Set.of());
    }

    /**
     * The variables a template may read, including what the selected set <i>provides</i>.
     *
     * <p>Capabilities are here for the same reason {@code when} expressions can ask about them
     * (§4): a template that needs to know whether this project builds with Maven must not do it by
     * naming {@code build-maven}, because recipes never name each other. It asks what the project
     * has — {@code capabilities contains 'maven-build'} — and the question survives a recipe being
     * renamed, replaced or joined by a second one that provides the same thing.
     *
     * <p>Sorted, because §4's byte-identical guarantee covers anything a template can iterate.
     */
    public static TemplateVariables of(
            Selection selection,
            Map<String, OptionValue> effectiveOptions,
            java.util.Collection<dev.kitbash.core.recipe.Capability> capabilities) {
        Builder builder = builder();
        effectiveOptions.forEach((id, value) -> builder.put(id, value.templateValue()));
        selection.variables().forEach(builder::put);
        builder.put("projectName", selection.projectName());
        builder.put(
                "capabilities",
                capabilities.stream()
                        .map(dev.kitbash.core.recipe.Capability::name)
                        .sorted()
                        .toList());
        return builder.build();
    }

    /**
     * The whole map, for a caller that has to send it somewhere the engine cannot follow.
     *
     * <p>Public since kitbash-48, for exactly one caller: the sandbox, which renders a contributed
     * recipe in another process and has to serialise these across. §13's rule that the map holds
     * validated primitives only is what makes that possible at all — JSON cannot carry a live
     * object, so the boundary enforces the rule rather than merely preserving it.
     */
    public Map<String, Object> asMap() {
        return values;
    }

    public Object get(String name) {
        return values.get(name);
    }

    public boolean contains(String name) {
        return values.containsKey(name);
    }

    public int size() {
        return values.size();
    }

    public static final class Builder {

        private final Map<String, Object> values = new LinkedHashMap<>();

        public Builder put(String name, Object value) {
            Objects.requireNonNull(name, "name");
            values.put(name, validate(name, value));
            return this;
        }

        public TemplateVariables build() {
            return new TemplateVariables(values);
        }

        private static Object validate(String name, Object value) {
            return switch (value) {
                case null -> "";
                case String text -> text;
                case Boolean flag -> flag;
                case Number number -> number;
                case List<?> list -> {
                    list.forEach(element -> {
                        if (!(element instanceof String)) {
                            throw refuse(name, element);
                        }
                    });
                    yield List.copyOf(list);
                }
                default -> throw refuse(name, value);
            };
        }

        private static IllegalArgumentException refuse(String name, Object value) {
            return new IllegalArgumentException("Template variable '" + name + "' would expose a "
                    + value.getClass().getName()
                    + ". The variable map holds validated primitives only — strings, booleans, numbers "
                    + "and lists of strings (plan §13).");
        }
    }
}
