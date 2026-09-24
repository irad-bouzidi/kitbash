package dev.kitbash.catalog.contributed;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.PatchRule;
import dev.kitbash.core.recipe.Recipe;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a contributed recipe may not do (threat model §3.4, §3.5; ADR 0004).
 *
 * <p>These are the two controls the sandbox cannot provide, and the reason is structural: §13's
 * isolation protects the <i>generator host</i>, while a poisoned dependency coordinate and an
 * exfiltrating CI job attack the <i>generated project</i> and the person who runs it. Their payload
 * is the generator's legitimate output — a coordinate written into a build file is
 * {@code addDependency} working exactly as designed — so no process boundary constrains them. A
 * build that shipped the sandbox and called the threat model satisfied would have shipped the easy
 * half.
 *
 * <p>§47 supplies a human reviewer and the reviewer is necessary. But a review is only as good as
 * what it is asked to look at, and asking somebody to spot
 * {@code com.fasterxml.jackson:jackson-databind} beside the real
 * {@code com.fasterxml.jackson.core:jackson-databind} is asking them to be a scanner. These rules
 * exist so the reviewer is checking a judgement rather than performing a search.
 *
 * <p>Enforced at <b>submission</b>, not at render. A recipe that would be refused should never
 * reach a review queue, because a queue full of things that cannot be approved is a queue people
 * stop reading carefully.
 */
public final class ContributedRecipeRules {

    /**
     * Capabilities a contributed recipe may not declare.
     *
     * <p>{@code ci} is threat 3.5 and the whole of it: a generated job runs in the user's pipeline
     * with the user's secrets, and the verification matrix lints generated CI with
     * {@code actionlint} rather than executing it — so an exfiltrating job is inert during
     * verification and fires afterwards. Nothing short of not emitting the job closes that.
     *
     * <p>A recipe that genuinely needs a CI job is a merge request against the shipped catalog,
     * where the review is the one git already gives. That costs contributed recipes something
     * useful, and the trade is made here deliberately rather than discovered later.
     */
    private static final Set<String> FORBIDDEN_CAPABILITIES = Set.of("ci");

    /**
     * Patch targets a contributed recipe may not name.
     *
     * <p>Separate from the capability check because they are separate evasions. Declaring
     * {@code ci} is the honest route; a {@code mergeYaml} straight at {@code .github/workflows}
     * from a recipe that declares no capability at all is the other one, and blocking only the
     * first would block only the honest attacker.
     */
    private static final List<String> FORBIDDEN_TARGET_FRAGMENTS =
            List.of(".github/", ".gitlab-ci", "azure-pipelines", "bitbucket-pipelines", "jenkinsfile", ".circleci/");

    private final Set<String> allowedCoordinates;

    /**
     * @param allowedCoordinates {@code group:name} pairs, <b>without versions</b>. Deliberately:
     *     pinning versions here would make this list a second dependency-freshness problem and it
     *     would rot, while the threat is a coordinate nobody recognises rather than a stale one —
     *     and a version that moves is already §12's job.
     */
    public ContributedRecipeRules(Set<String> allowedCoordinates) {
        this.allowedCoordinates = Set.copyOf(allowedCoordinates);
    }

    /**
     * Every reason this recipe may not be submitted, or an empty list.
     *
     * <p>All of them, not the first. A contributor who fixes one coordinate and resubmits to be
     * told about the next has been given a guessing game, and a reviewer reading a half-refused
     * submission cannot tell how much is wrong with it.
     */
    public List<String> refusalsFor(Recipe recipe) {
        List<String> refusals = new ArrayList<>();

        if (!recipe.id().contributed()) {
            // Belt and braces: the rules below are the ones that make an unnamespaced contributed
            // recipe dangerous, and an id without an '@' would also be indistinguishable from a
            // shipped one in the wizard, the lock and the logs (§6.3).
            refusals.add("its id is '" + recipe.id()
                    + "', and a contributed recipe's id must be namespaced as '@namespace/name'");
        }

        if (recipe.hasHook()) {
            // The boundary §47's implementation notes call "the main thing standing between this
            // feature and an arbitrary-code-execution endpoint". A hook's code is registered in
            // core and reviewed with the source; declaring `hook: true` from a contributed recipe
            // could only ever bind to somebody else's implementation or to nothing.
            refusals.add("it declares a hook, and hook code is registered in the application rather "
                    + "than supplied by a recipe");
        }

        recipe.provides().stream()
                .map(Capability::name)
                .filter(name -> FORBIDDEN_CAPABILITIES.contains(name.toLowerCase(Locale.ROOT)))
                .forEach(name -> refusals.add("it provides the '" + name
                        + "' capability, which contributed recipes may not: a generated CI job runs in "
                        + "your pipeline with your secrets"));

        for (PatchRule rule : recipe.patches()) {
            refusalFor(rule.op()).ifPresent(refusals::add);
        }

        return List.copyOf(refusals);
    }

    private java.util.Optional<String> refusalFor(PatchOp op) {
        String target = op.target() == null ? "" : op.target().toLowerCase(Locale.ROOT);
        for (String fragment : FORBIDDEN_TARGET_FRAGMENTS) {
            if (target.contains(fragment)) {
                return java.util.Optional.of("its " + op.operation() + " targets '" + op.target()
                        + "', and contributed recipes may not modify CI configuration");
            }
        }

        if (op instanceof PatchOp.AddDependency dependency) {
            String groupAndName = withoutVersion(dependency.coordinate());
            if (!allowedCoordinates.contains(groupAndName)) {
                return java.util.Optional.of("it adds the dependency '" + dependency.coordinate()
                        + "', which is not on the allowlist (the allowed coordinate is matched as '"
                        + groupAndName + "')");
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * {@code group:name} from {@code group:name}, {@code group:name:version} or a Gradle-style
     * coordinate.
     *
     * <p>Matching on two segments rather than the whole string is what lets the allowlist ignore
     * versions. The subtle part is that a coordinate with <i>fewer</i> than two segments must not
     * quietly match something: it is returned unchanged and will simply not be on the list, which
     * refuses it. Normalising a malformed coordinate into a well-formed one is how an allowlist
     * acquires a hole.
     */
    private static String withoutVersion(String coordinate) {
        int first = coordinate.indexOf(':');
        if (first < 0) {
            return coordinate;
        }
        int second = coordinate.indexOf(':', first + 1);
        return second < 0 ? coordinate : coordinate.substring(0, second);
    }
}
