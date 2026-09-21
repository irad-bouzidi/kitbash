package dev.kitbash.render;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.pipeline.RenderStage;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.Workspace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one implementation of §6's stage 4 that knows Pebble exists.
 *
 * <p>Thin on purpose: that is the point of the seam. The pipeline's shape lives in {@code core}
 * with the other six stages, and everything template-specific — the sandbox, the filters, the path
 * templating — stays behind this interface in {@code render} (§6).
 */
public final class PebbleRenderStage implements RenderStage {

    @Override
    public Workspace render(FilePlan plan, Selection selection, Resolution resolution) {
        TemplateVariables variables =
                TemplateVariables.of(selection, resolution.effectiveOptions(), resolution.capabilities());
        return Renderer.render(plan, variables, perRecipe(resolution, variables));
    }

    @Override
    public List<PatchOp> renderPatches(List<PatchOp> ops, Selection selection, Resolution resolution) {
        TemplateVariables variables =
                TemplateVariables.of(selection, resolution.effectiveOptions(), resolution.capabilities());
        Map<RecipeId, TemplateVariables> perRecipe = perRecipe(resolution, variables);
        TemplateEngine engine = TemplateEngine.over(TemplateRegistry.empty());
        return ops.stream()
                .map(op -> new PatchOpRenderer(engine, perRecipe.getOrDefault(op.owner(), variables)).render(op))
                .toList();
    }

    /** A recipe's templates see its own manifest facts on top of the shared variable map. */
    private static Map<RecipeId, TemplateVariables> perRecipe(Resolution resolution, TemplateVariables base) {
        Map<RecipeId, TemplateVariables> overlay = new LinkedHashMap<>();
        for (Recipe recipe : resolution.recipes()) {
            overlay.put(recipe.id(), base.forRecipe(recipe));
        }
        return overlay;
    }
}
