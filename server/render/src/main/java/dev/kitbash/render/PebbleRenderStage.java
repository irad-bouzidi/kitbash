package dev.kitbash.render;

import dev.kitbash.core.pipeline.RenderStage;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.selection.OptionValue;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.Workspace;
import java.util.Map;

/**
 * The one implementation of §6's stage 4 that knows Pebble exists.
 *
 * <p>It is three lines because that is the whole point of the seam: the pipeline's shape lives in
 * {@code core} with the other six stages, and everything template-specific — the sandbox, the
 * filters, the path templating — stays behind this interface in {@code render} (§6).
 */
public final class PebbleRenderStage implements RenderStage {

    @Override
    public Workspace render(FilePlan plan, Selection selection, Map<String, OptionValue> effectiveOptions) {
        return Renderer.render(plan, TemplateVariables.of(selection, effectiveOptions));
    }
}
