package dev.kitbash.render;

import dev.kitbash.core.patch.PatchOp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the strings inside a patch operation, so the appliers never see a template.
 *
 * <p>A recipe declaring an environment variable writes {@code {{ envPrefix }}_DB_URL}; a README
 * fragment names the package it laid down. Those have to be resolved somewhere, and doing it here
 * means every applier can treat its input as literal — which is the difference between eight
 * appliers and eight appliers that each know about a template engine.
 *
 * <p>The switch is exhaustive with no {@code default}, like every other dispatch over {@link
 * PatchOp}: a ninth operation has to decide what rendering means for it before it compiles.
 */
final class PatchOpRenderer {

    private final TemplateEngine engine;
    private final TemplateVariables variables;

    PatchOpRenderer(TemplateEngine engine, TemplateVariables variables) {
        this.engine = engine;
        this.variables = variables;
    }

    PatchOp render(PatchOp op) {
        String owner = op.owner().value();
        return switch (op) {
            case PatchOp.AddDependency value ->
                new PatchOp.AddDependency(
                        value.owner(),
                        text(value.target(), owner),
                        text(value.configuration(), owner),
                        text(value.coordinate(), owner),
                        text(value.versionRef(), owner));
            case PatchOp.MergeYaml value ->
                new PatchOp.MergeYaml(value.owner(), text(value.target(), owner), tree(value.content(), owner));
            case PatchOp.MergeJson value ->
                new PatchOp.MergeJson(value.owner(), text(value.target(), owner), tree(value.content(), owner));
            case PatchOp.AddScript value ->
                new PatchOp.AddScript(
                        value.owner(),
                        text(value.target(), owner),
                        text(value.name(), owner),
                        text(value.command(), owner));
            case PatchOp.InsertAtMarker value ->
                new PatchOp.InsertAtMarker(
                        value.owner(),
                        text(value.target(), owner),
                        text(value.marker(), owner),
                        lines(value.lines(), owner));
            case PatchOp.AppendLines value ->
                new PatchOp.AppendLines(value.owner(), text(value.target(), owner), lines(value.lines(), owner));
            case PatchOp.AddEnvVar value ->
                new PatchOp.AddEnvVar(
                        value.owner(),
                        text(value.target(), owner),
                        text(value.composeTarget(), owner),
                        text(value.composeService(), owner),
                        text(value.name(), owner),
                        text(value.value(), owner),
                        text(value.composeValue(), owner),
                        text(value.comment(), owner));
            case PatchOp.AddComposeService value ->
                new PatchOp.AddComposeService(
                        value.owner(),
                        text(value.target(), owner),
                        text(value.serviceName(), owner),
                        tree(value.definition(), owner),
                        lines(value.dependsOn(), owner));
        };
    }

    private String text(String source, String owner) {
        if (source == null || !source.contains("{{")) {
            // Most patch strings are literal; skipping the engine for those keeps the common case
            // free and keeps the template registry small.
            return source;
        }
        return engine.render(TemplateRegistry.of(Map.of(source, source)), source, owner, variables);
    }

    private List<String> lines(List<String> source, String owner) {
        List<String> rendered = new ArrayList<>(source.size());
        source.forEach(line -> rendered.add(text(line, owner)));
        return rendered;
    }

    /** Values nested in a YAML or JSON fragment are templated too, at any depth. */
    private Object value(Object source, String owner) {
        return switch (source) {
            case null -> null;
            case String string -> text(string, owner);
            case Map<?, ?> map -> tree(mapOf(map), owner);
            case List<?> list ->
                list.stream().map(element -> value(element, owner)).toList();
            default -> source;
        };
    }

    private Map<String, Object> tree(Map<String, Object> source, String owner) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        source.forEach((key, entry) -> rendered.put(text(key, owner), value(entry, owner)));
        return rendered;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
