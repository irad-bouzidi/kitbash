package dev.kitbash.core.hook;

import dev.kitbash.core.patch.PatchOp;
import java.util.List;

/**
 * The narrow, typed escape hatch for the few things that are genuinely awkward as data (§4).
 *
 * <p>A handful of computations do not fit a manifest: a Gradle version catalog assembled from the
 * union of the selected recipes, a lockfile, a compose service graph. Rather than bending the
 * manifest format into a programming language, a recipe may declare that a hook exists for it — and
 * the hook is <b>registered in {@code core}</b>, never loaded from the recipe directory. A recipe
 * directory can say it <i>has</i> a hook; it cannot supply code. User-supplied code is
 * {@code kitbash-47} and carries a threat model this does not.
 *
 * <p>Hooks run in the plan stage and return ordinary patch ops, subject to the same idempotency and
 * ownership rules as any other op, so nothing downstream needs to know a hook was involved.
 *
 * <p>The rule matters as much as the interface: <b>if more than about three recipes need a hook,
 * the manifest format is missing a feature and should gain one instead.</b> {@code docs/hooks.md}
 * states that as policy and lists every registered hook.
 */
public interface RecipeHook {

    /** The recipe this hook belongs to. Every op it returns must be owned by this id. */
    String recipeId();

    /** Pure: no I/O, no shell, no clock. Same context in, equal ops out, every time. */
    List<PatchOp> contribute(PlanContext context);
}
