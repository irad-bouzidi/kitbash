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

    private final java.util.function.Supplier<Catalog> catalogSource;
    private final java.util.function.Supplier<RecipeContent> contentSource;
    private final RenderStage renderStage;
    private final Caps caps;

    /**
     * A pipeline over a catalog that can change under it (kitbash-48).
     *
     * <p>Suppliers rather than values, and only because approving a contributed recipe has to take
     * effect without a restart. The alternative was to make every consumer of this class ask a
     * holder for a fresh pipeline, which is the same indirection spread over nine call sites
     * instead of one — and nine places to forget it.
     *
     * <p>Nothing else changes. A {@link Catalog} is still immutable, and any method that asks it
     * more than one question holds it in a local first — so a swap between two requests can never
     * be seen halfway through one.
     */
    public GenerationPipeline(
            java.util.function.Supplier<Catalog> catalogSource,
            java.util.function.Supplier<RecipeContent> contentSource,
            RenderStage renderStage,
            Caps caps) {
        this.catalogSource = Objects.requireNonNull(catalogSource, "catalogSource");
        this.contentSource = Objects.requireNonNull(contentSource, "contentSource");
        this.renderStage = Objects.requireNonNull(renderStage, "renderStage");
        this.caps = Objects.requireNonNull(caps, "caps");
    }

    /** A pipeline over a catalog that will not change, which is every caller but the API's. */
    public GenerationPipeline(Catalog catalog, RecipeContent content, RenderStage renderStage, Caps caps) {
        this(() -> catalog, () -> content, renderStage, caps);
    }

    public static GenerationPipeline over(Catalog catalog, RecipeContent content, RenderStage renderStage) {
        return new GenerationPipeline(catalog, content, renderStage, Caps.standard());
    }

    /** The same, over sources that may answer differently after a contributed recipe is approved. */
    public static GenerationPipeline over(
            java.util.function.Supplier<Catalog> catalog,
            java.util.function.Supplier<RecipeContent> content,
            RenderStage renderStage) {
        return new GenerationPipeline(catalog, content, renderStage, Caps.standard());
    }

    public Catalog catalog() {
        return catalogSource.get();
    }

    /** Stage 1. Unknown recipe ids are rejected here, before anything has been resolved. */
    public Selection parse(SelectionEnvelope envelope) {
        return SelectionParser.parse(catalog(), envelope);
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
        // One read for both questions, for the reason generate() gives: parse and resolve must
        // see the same catalog or the diagnostics can describe two different ones.
        Catalog catalog = catalogSource.get();
        try {
            return Resolver.resolve(catalog, SelectionParser.parse(catalog, envelope));
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
        return Planner.plan(resolution, contentSource.get(), caps);
    }

    /**
     * Stages 1–5, which is what {@code POST /api/v1/preview} serves: the file tree and any single
     * file's contents, without the git skeleton or the zip.
     */
    public Workspace preview(SelectionEnvelope envelope) {
        Caps.Deadline deadline = caps.deadline();
        Catalog catalog = catalogSource.get();
        Selection selection = SelectionParser.parse(catalog, envelope);
        Resolution resolution = requireResolvable(Resolver.resolve(catalog, selection));
        return renderAndPatch(resolution, selection, deadline);
    }

    /** All seven stages. The result knows how to stream itself. */
    public GeneratedProject generate(SelectionEnvelope envelope) {
        Caps.Deadline deadline = caps.deadline();
        Selection selection = parse(envelope);
        // Read once and held. Since kitbash-48 the catalog can be replaced between requests, and
        // this method asks it two questions — what resolves, and what digest to record. Asking
        // twice would let an approval landing in between put a digest in the lock that does not
        // describe the catalog the resolution came from, which is an unreproducible generation
        // and the hardest kind of bug to ever see again.
        Catalog catalog = catalogSource.get();
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
