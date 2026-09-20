package dev.kitbash.core.pipeline;

import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.selection.OptionValue;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.Workspace;
import java.util.Map;

/**
 * Stage 4, as a seam.
 *
 * <p>§6 puts the template engine in {@code render} and everything else in {@code core}, which would
 * leave the seven stages with nowhere to be assembled. Making the render stage an interface keeps
 * the pipeline's shape in {@code core}, where the other six stages live and where the tests are
 * fast, and lets {@code render} supply the one implementation that knows Pebble exists.
 *
 * <p>It also means the pipeline can be tested end to end without a template engine at all, which is
 * how the plan, patch, post-process and package stages get covered without Pebble in the way.
 */
public interface RenderStage {

    Workspace render(FilePlan plan, Selection selection, Map<String, OptionValue> effectiveOptions);
}
