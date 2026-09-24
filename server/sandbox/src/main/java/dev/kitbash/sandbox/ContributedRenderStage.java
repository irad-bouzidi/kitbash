package dev.kitbash.sandbox;

import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.pipeline.RenderStage;
import dev.kitbash.core.plan.FileEntry;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.Workspace;
import dev.kitbash.render.PebbleRenderStage;
import dev.kitbash.render.Renderer;
import dev.kitbash.render.TemplateVariables;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stage 4, routed by where the recipe came from (kitbash-48; threat model §6.4).
 *
 * <p>A shipped recipe's templates render in process, as they always have. A contributed recipe's
 * render in the sandbox, one subprocess per recipe, and come back as finished strings that
 * {@link Renderer} then treats exactly like its own output — the {@code .peb} strip, the rendered
 * path check, the binary guard and the plan-order collection are one code path for both, because
 * two would drift on precisely the checks that matter most for untrusted input.
 *
 * <h2>What the split actually buys</h2>
 *
 * <p>More than "the sandbox ran it". {@code Renderer} builds one template registry per pass, and
 * that registry is what an {@code include} can reach. Entries rendered here are left out of it, so
 * a contributed template is not merely routed away from the in-process engine — it is not loadable
 * by it, and no shipped template can include one.
 *
 * <p>The converse holds too, and is stronger than the threat model promised. Each contributed
 * recipe is sent to the sandbox with <b>only its own templates</b>, so it cannot include another
 * contributed recipe's either. §3.1 worried about a contributed template reading what it was not
 * shipped with; one recipe per process answers it completely.
 *
 * <p>Shipped recipes keep rendering in process deliberately. They are not the untrusted input, and
 * a subprocess per generation would cost every user a JVM start to defend against a repository we
 * control.
 */
public final class ContributedRenderStage implements RenderStage {

    private final PebbleRenderStage inProcess = new PebbleRenderStage();
    private final SandboxedRenderer sandbox;

    public ContributedRenderStage(SandboxedRenderer sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public Workspace render(FilePlan plan, Selection selection, Resolution resolution) {
        List<FileEntry> contributed = plan.effectiveEntries().stream()
                .filter(entry -> entry.owner().contributed())
                .toList();

        if (contributed.isEmpty()) {
            // The overwhelmingly common case, and it must cost nothing: no subprocess, no map, and
            // the same call the pipeline made before this class existed.
            return inProcess.render(plan, selection, resolution);
        }

        TemplateVariables base =
                TemplateVariables.of(selection, resolution.effectiveOptions(), resolution.capabilities());
        Map<RecipeId, TemplateVariables> perRecipe = perRecipe(resolution, base);

        Map<String, String> rendered = new LinkedHashMap<>();
        byRecipe(contributed)
                .forEach((recipeId, entries) ->
                        rendered.putAll(renderInSandbox(recipeId, entries, perRecipe.getOrDefault(recipeId, base))));

        return disclose(
                Renderer.render(plan, base, perRecipe, rendered),
                byRecipe(contributed).keySet());
    }

    /** The README marker every recipe's stack line goes above (§4's conventions). */
    private static final String STACK_MARKER = "<!-- kitbash:stack -->";

    private static final String README = "README.md";

    /**
     * Says in the project which recipes came from outside the repository (§47, kitbash-48).
     *
     * <p>Written by the generator, not by the recipe, and that is the whole design. A contributed
     * recipe cannot carry patches at all here, so it could not add its own line — and it should
     * not be able to. Disclosure a recipe writes about itself is disclosure an author can word,
     * shorten or forget, which is not disclosure.
     *
     * <p>The lock needs no equivalent: §6.3 put the namespace inside the recipe id, so every
     * contributed recipe is already visible there as {@code @namespace/name} with nothing extra to
     * maintain. This is the half a human reads.
     */
    private static Workspace disclose(Workspace workspace, java.util.Set<RecipeId> contributed) {
        if (!workspace.contains(README)) {
            // Only `project-root` promises a README, and a selection without it is legal. Nothing
            // to disclose into is not an error — but it must not be a silent success either, so
            // the lock remains the disclosure that always exists.
            return workspace;
        }

        String readme = new String(workspace.get(README).content(), StandardCharsets.UTF_8);
        if (!readme.contains(STACK_MARKER)) {
            return workspace;
        }

        StringBuilder note = new StringBuilder("> **Contributed recipes.** This project was built with "
                + "recipes that were submitted and approved rather than shipped with the generator:\n>\n");
        // Sorted: two runs of one selection must produce identical bytes (§4), and a set's
        // iteration order is not a promise.
        contributed.stream()
                .sorted()
                .forEach(id -> note.append("> - `").append(id.value()).append("`\n"));
        note.append(">\n> They are listed in this project's lock by the same ids.\n\n")
                .append(STACK_MARKER);

        // Above the marker, like every other contribution to this section, so it reads before the
        // stack rather than after it.
        String disclosed = readme.replace(STACK_MARKER, note.toString());
        workspace.put(
                README,
                new dev.kitbash.core.workspace.GeneratedFile(
                        disclosed.getBytes(StandardCharsets.UTF_8),
                        workspace.get(README).executable()));
        return workspace;
    }

    /**
     * Patch content is templated too, and a contributed recipe's patches are as untrusted as its
     * files.
     *
     * <p>Easy to miss, because the ops look like data: a {@code mergeYaml} body or an
     * {@code appendLines} entry is a template, and rendering one in process would leave a hole
     * exactly where somebody would look for it.
     */
    @Override
    public List<PatchOp> renderPatches(List<PatchOp> ops, Selection selection, Resolution resolution) {
        if (ops.stream().noneMatch(op -> op.owner().contributed())) {
            return inProcess.renderPatches(ops, selection, resolution);
        }
        // Not supported rather than silently rendered unsafely. §6.2 already bars contributed
        // recipes from the patch targets that carry the worst consequences, and the remaining
        // ones are not worth a second sandbox protocol until a contributed recipe needs one —
        // but "not worth building yet" must fail loudly, not quietly fall through to the engine
        // this class exists to keep them away from.
        String owner = ops.stream()
                .filter(op -> op.owner().contributed())
                .map(op -> op.owner().value())
                .findFirst()
                .orElseThrow();
        throw GenerationError.renderFailed(
                        owner,
                        "recipe.yaml",
                        0,
                        "contributed recipes cannot carry patches yet: their content is templated, and "
                                + "this server will not render an untrusted template in process")
                .asException();
    }

    private Map<String, String> renderInSandbox(
            RecipeId recipeId, List<FileEntry> entries, TemplateVariables variables) {
        // Sorted, so two runs of the same selection send the sandbox byte-identical input. §4's
        // determinism has to survive the process boundary, and a hash-ordered map is the usual way
        // it does not.
        Map<String, String> templates = new TreeMap<>();
        for (FileEntry entry : entries) {
            templates.put(Renderer.pathTemplateName(entry), entry.path());
            if (entry.templated()) {
                templates.put(Renderer.bodyTemplateName(entry), new String(entry.read(), StandardCharsets.UTF_8));
            }
        }

        Map<String, String> rendered = sandbox.render(recipeId.value(), templates, variables.asMap());

        // Every template asked for must come back. A short answer would otherwise fall through to
        // Renderer's engine, which no longer holds these templates, and surface as a confusing
        // "no such template" rather than as the sandbox having misbehaved.
        if (!rendered.keySet().containsAll(templates.keySet())) {
            throw new SandboxRefusedException(
                    "SANDBOX_INCOMPLETE",
                    "The sandbox returned " + rendered.size() + " of " + templates.size() + " templates for " + recipeId
                            + ".",
                    "This is a fault in the server rather than in the recipe. Nothing was generated.");
        }
        return rendered;
    }

    private static Map<RecipeId, List<FileEntry>> byRecipe(List<FileEntry> entries) {
        Map<RecipeId, List<FileEntry>> grouped = new java.util.TreeMap<>();
        entries.forEach(entry -> grouped.computeIfAbsent(entry.owner(), ignored -> new java.util.ArrayList<>())
                .add(entry));
        return grouped;
    }

    private static Map<RecipeId, TemplateVariables> perRecipe(Resolution resolution, TemplateVariables base) {
        Map<RecipeId, TemplateVariables> overlay = new LinkedHashMap<>();
        for (Recipe recipe : resolution.recipes()) {
            overlay.put(recipe.id(), base.forRecipe(recipe));
        }
        return overlay;
    }
}
