package dev.kitbash.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a selection's steps are: the build commands a combination has to survive.
 *
 * <p>This lives on its own because two callers need the same answer and must not be allowed to
 * drift. The nightly matrix derives ninety-eight cells from the catalog (§35); {@code kitbash-37}
 * lets a user ask about a combination nobody enumerated. If the on-demand job ran a smaller set of
 * commands than the matrix does, a green answer would mean something weaker than a green cell — and
 * the user asking "does my combination build?" would have been told yes on a lesser question.
 *
 * <p>So the steps are computed from the selection's options, once, here.
 */
public final class CellSteps {

    private CellSteps() {}

    /** The steps a project with these options is built with, in the order they must run. */
    public static List<Cell.Step> forOptions(Map<String, Object> options) {
        List<Cell.Step> steps = new ArrayList<>();
        if (options.containsKey("backend")) {
            boolean maven = "build-maven".equals(options.get("buildTool"));
            boolean typedClient = Boolean.TRUE.equals(options.get("typedClient"));
            List<String> commands = new ArrayList<>();
            // Maven generates the client inside one lifecycle, so the profile replaces the plain
            // build rather than following it; Gradle's is a separate task after it.
            commands.add(
                    maven
                            ? (typedClient ? "./mvnw -B -Pclient verify" : "./mvnw -B verify")
                            : "./gradlew build --no-daemon");
            if (typedClient && !maven) {
                commands.add("./gradlew generateApiClient --no-daemon");
            }
            steps.add(new Cell.Step("jvm", ".", commands));
        }
        if (options.containsKey("frontend")) {
            steps.add(new Cell.Step(
                    "node",
                    "frontend",
                    List.of(
                            "pnpm install --frozen-lockfile",
                            "pnpm lint",
                            "pnpm typecheck",
                            "pnpm test",
                            "pnpm build")));
        }
        // Only when a provider was chosen. Every enumerated cell chooses one, so the matrix is
        // unaffected; a requested selection need not, and linting a pipeline that was never
        // rendered would fail the run for the one thing the user declined.
        if (options.containsKey("ci")) {
            steps.add(new Cell.Step(
                    "ci",
                    ".",
                    List.of(
                            "ci-github".equals(options.get("ci"))
                                    ? "actionlint -no-color .github/workflows/ci.yml"
                                    : "check-jsonschema --builtin-schema vendor.gitlab-ci .gitlab-ci.yml")));
        }
        return List.copyOf(steps);
    }

    /**
     * Whether the steps have to share one working tree.
     *
     * <p>True exactly when the JVM build produces something the frontend build consumes, which
     * today is the typed client and nothing else. Getting this wrong is not a slow cell but a
     * wrong one: the Node step would build a project whose {@code src/lib/api} was never written.
     */
    public static boolean sharedWorkspace(Map<String, Object> options) {
        return options.containsKey("frontend") && Boolean.TRUE.equals(options.get("typedClient"));
    }
}
