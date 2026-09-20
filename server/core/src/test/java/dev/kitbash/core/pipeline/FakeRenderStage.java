package dev.kitbash.core.pipeline;

import dev.kitbash.core.plan.FileEntry;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.selection.OptionValue;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A render stage with no template engine: {@code {{ name }}} is replaced by the variable of that
 * name, and the {@code .peb} suffix is stripped.
 *
 * <p>Deliberately not Pebble. These tests are about the other six stages, and the seam exists so
 * they can be. The sandbox, the filters and the real path templating have their own suite in
 * {@code render}, and a determinism test over the whole stack with the real engine lives there too.
 */
final class FakeRenderStage implements RenderStage {

    @Override
    public Workspace render(FilePlan plan, Selection selection, Map<String, OptionValue> effectiveOptions) {
        Workspace workspace = new Workspace();
        for (FileEntry entry : plan.effectiveEntries()) {
            String path = substitute(strip(entry.path()), selection, effectiveOptions);
            byte[] content = entry.read();
            if (entry.templated()) {
                content = substitute(new String(content, StandardCharsets.UTF_8), selection, effectiveOptions)
                        .getBytes(StandardCharsets.UTF_8);
            }
            workspace.put(path, new GeneratedFile(content, entry.mode().executable()));
        }
        return workspace;
    }

    private static String strip(String path) {
        return path.endsWith(".peb") ? path.substring(0, path.length() - ".peb".length()) : path;
    }

    private static String substitute(String text, Selection selection, Map<String, OptionValue> options) {
        String result = text.replace("{{ projectName }}", selection.projectName());
        for (Map.Entry<String, String> variable : selection.variables().entrySet()) {
            result = result.replace("{{ " + variable.getKey() + " }}", variable.getValue());
            result = result.replace(
                    "{{ " + variable.getKey() + " | packagePath }}",
                    variable.getValue().replace('.', '/'));
        }
        for (Map.Entry<String, OptionValue> option : options.entrySet()) {
            result = result.replace(
                    "{{ " + option.getKey() + " }}",
                    String.valueOf(option.getValue().templateValue()));
        }
        return result;
    }
}
