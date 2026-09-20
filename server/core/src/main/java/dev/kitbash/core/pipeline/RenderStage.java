package dev.kitbash.core.pipeline;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.Workspace;
import java.util.List;

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

    Workspace render(FilePlan plan, Selection selection, Resolution resolution);

    /**
     * Patch content is templated too, and for the same reason file bodies are: a recipe declaring
     * an environment variable has to be able to write {@code {{ envPrefix }}_DB_URL}, and a README
     * fragment describing the stack has to be able to name the package it laid down.
     *
     * <p>Rendered as a separate step rather than during the plan stage, because the plan runs
     * before anything has been rendered and its job is to stay cheap (§6) — and because ops have to
     * reach the appliers with their variables already resolved, or every applier would need to know
     * that templates exist.
     *
     * <p>The whole {@link Resolution} is passed rather than just the option map so that a recipe's
     * own manifest facts — the framework version it emits, its recipe version — are available to
     * its templates. Without that, a README fragment naming "Spring Boot 3.5.5" would be a second
     * copy of a number the manifest already holds, and the two would drift.
     */
    List<PatchOp> renderPatches(List<PatchOp> ops, Selection selection, Resolution resolution);
}
