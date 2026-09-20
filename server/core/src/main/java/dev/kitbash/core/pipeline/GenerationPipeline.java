package dev.kitbash.core.pipeline;

import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.patch.PatchApplier;
import dev.kitbash.core.plan.Caps;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.plan.Planner;
import dev.kitbash.core.plan.RecipeContent;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.resolve.Resolver;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.core.selection.SelectionParser;
import dev.kitbash.core.workspace.Workspace;
import java.util.Objects;

/**
 * The seven stages of §6, wired as separate callable functions rather than one method.
 *
 * <pre>
 *   1 parse   →  2 resolve  →  3 plan  →  4 render  →  5 patch  →  6 post-process  →  7 package
 *   └──── validate() ────┘                     │
 *   └──────────────── preview() ───────────────┘
 *   └──────────────────────────── generate() ─────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The boundaries are what make {@code /validate} and {@code /preview} cheap, and that is why §6
 * specifies them rather than leaving the split to taste: the wizard calls {@code /validate} on
 * every debounced change, and it only runs stages 1–2 because those two stages are pure and touch
 * no template.
 *
 * <p>Each stage takes the previous stage's output as its only input. Anything a later stage needs
 * from an earlier one is threaded through the types — which is the discipline that stops seven
 * functions quietly reassembling themselves into one method with seven sections.
 */
public final class GenerationPipeline {

    private final Catalog catalog;
    private final RecipeContent content;
    private final RenderStage renderStage;
    private final Caps caps;

    public GenerationPipeline(Catalog catalog, RecipeContent content, RenderStage renderStage, Caps caps) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.content = Objects.requireNonNull(content, "content");
        this.renderStage = Objects.requireNonNull(renderStage, "renderStage");
        this.caps = Objects.requireNonNull(caps, "caps");
    }

    public static GenerationPipeline over(Catalog catalog, RecipeContent content, RenderStage renderStage) {
        return new GenerationPipeline(catalog, content, renderStage, Caps.standard());
    }

    public Catalog catalog() {
        return catalog;
    }

    /** Stage 1. Unknown recipe ids are rejected here, before anything has been resolved. */
    public Selection parse(SelectionEnvelope envelope) {
        return SelectionParser.parse(catalog, envelope);
    }

    /**
     * Stages 1–2, which is what {@code POST /api/v1/validate} serves (§8).
     *
     * <p>A parse failure is returned as a resolution carrying that one conflict rather than thrown,
     * because a wizard calling this on every keystroke wants a payload to render either way — an
     * exception at this boundary would make the common case (a half-typed package name) an error
     * response instead of an inline field message.
     */
    public Resolution validate(SelectionEnvelope envelope) {
        try {
            return Resolver.resolve(catalog, parse(envelope));
        } catch (GenerationException e) {
            return new Resolution(
                    java.util.List.of(),
                    java.util.Set.of(),
                    java.util.Map.of(),
                    java.util.Set.of(),
                    java.util.List.of(e.error()),
                    java.util.List.of());
        }
    }

    /** Stage 3 on its own, for callers that already have a resolution. */
    public FilePlan plan(Resolution resolution) {
        return Planner.plan(resolution, content, caps);
    }

    /**
     * Stages 1–5, which is what {@code POST /api/v1/preview} serves: the file tree and any single
     * file's contents, without the git skeleton or the zip.
     */
    public Workspace preview(SelectionEnvelope envelope) {
        Caps.Deadline deadline = caps.deadline();
        Selection selection = parse(envelope);
        Resolution resolution = requireResolvable(Resolver.resolve(catalog, selection));
        return renderAndPatch(resolution, selection, deadline);
    }

    /** All seven stages. The result knows how to stream itself. */
    public GeneratedProject generate(SelectionEnvelope envelope) {
        Caps.Deadline deadline = caps.deadline();
        Selection selection = parse(envelope);
        Resolution resolution = requireResolvable(Resolver.resolve(catalog, selection));

        Workspace workspace = renderAndPatch(resolution, selection, deadline);
        String commitId = PostProcessor.postProcess(workspace);
        deadline.check("post-process");

        return new GeneratedProject(
                workspace,
                resolution,
                resolution.lock(catalog.digest()),
                selection.hash(),
                commitId,
                selection.projectName());
    }

    private Workspace renderAndPatch(Resolution resolution, Selection selection, Caps.Deadline deadline) {
        FilePlan plan = plan(resolution);
        deadline.check("plan");
        Workspace workspace = renderStage.render(plan, selection, resolution);
        deadline.check("render");
        PatchApplier.apply(workspace, renderStage.renderPatches(plan.patches(), selection, resolution));
        deadline.check("patch");
        return workspace;
    }

    /**
     * Generating from an unresolvable selection would produce a project missing whatever the
     * conflict was about, so the first conflict is raised instead. {@code /validate} is the
     * endpoint that returns them all.
     */
    private static Resolution requireResolvable(Resolution resolution) {
        if (!resolution.valid()) {
            throw new GenerationException(resolution.firstConflict());
        }
        if (resolution.recipes().isEmpty()) {
            // An empty zip is worse than an error: it looks like the generator worked.
            throw dev.kitbash.core.error.GenerationError.emptySelection().asException();
        }
        return resolution;
    }
}
