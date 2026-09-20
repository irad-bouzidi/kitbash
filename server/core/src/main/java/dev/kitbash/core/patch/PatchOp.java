package dev.kitbash.core.patch;

import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.util.Ordered;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The eight typed, format-aware edits recipes use to modify files other recipes produced (§4).
 *
 * <p>This is the type that makes composition real. Several recipes need to touch {@code
 * build.gradle.kts}, {@code application.yml}, {@code compose.yaml}, {@code package.json} and {@code
 * .gitignore}; §4 is blunt about the alternative — free-text appending produces broken syntax
 * within a week — so every edit here is parsed, merged and re-serialised by an applier that
 * understands the format.
 *
 * <p><b>Why sealed.</b> The appliers dispatch with a pattern-matching {@code switch} carrying no
 * {@code default} branch. Adding a ninth operation therefore fails compilation in every place that
 * must learn to handle it, rather than falling through to a silent no-op — which is what a {@code
 * default} branch guarantees eventually. §4 caps the set at these eight on purpose: a recipe that
 * seems to need a ninth is a design discussion, not a quiet addition.
 *
 * <p>Every op names both the recipe that owns it and the file it targets, so a failure can say
 * which recipe wanted what (§14), and so an op against a file no selected recipe produced fails at
 * validate time instead of silently.
 */
public sealed interface PatchOp {

    /** The recipe that contributed this op. Present on every variant so §14 errors can name it. */
    RecipeId owner();

    /** The project-relative path this op edits. */
    String target();

    /** The §4 operation name, as it appears in a manifest and in error messages. */
    String operation();

    /**
     * Inserts a dependency into the right block of a build file, deduping against what is already
     * there and referencing the version catalog rather than a literal version where one exists.
     */
    record AddDependency(RecipeId owner, String target, String configuration, String coordinate, String versionRef)
            implements PatchOp {

        public AddDependency {
            requireOwnerAndTarget(owner, target);
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(coordinate, "coordinate");
        }

        @Override
        public String operation() {
            return "addDependency";
        }
    }

    /**
     * Deep-merges a YAML fragment, failing on a scalar collision rather than picking a winner: two
     * recipes silently overwriting the same {@code application.yml} key is precisely the bug class
     * this design exists to prevent (§4).
     */
    record MergeYaml(RecipeId owner, String target, Map<String, Object> content) implements PatchOp {

        public MergeYaml {
            requireOwnerAndTarget(owner, target);
            content = Ordered.copyOf(content);
        }

        @Override
        public String operation() {
            return "mergeYaml";
        }
    }

    /** Deep-merges a JSON fragment; arrays union rather than replace. */
    record MergeJson(RecipeId owner, String target, Map<String, Object> content) implements PatchOp {

        public MergeJson {
            requireOwnerAndTarget(owner, target);
            content = Ordered.copyOf(content);
        }

        @Override
        public String operation() {
            return "mergeJson";
        }
    }

    /** Adds an npm script, failing loudly when the name is already taken. */
    record AddScript(RecipeId owner, String target, String name, String command) implements PatchOp {

        public AddScript {
            requireOwnerAndTarget(owner, target);
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(command, "command");
        }

        @Override
        public String operation() {
            return "addScript";
        }
    }

    /**
     * Inserts lines at a {@code // kitbash:…} marker placed by whichever recipe owns the target. A
     * missing marker is an error naming that recipe — markers are a contract between recipes, and
     * an unmet contract should not quietly degrade into an append at the end of the file.
     */
    record InsertAtMarker(RecipeId owner, String target, String marker, List<String> lines) implements PatchOp {

        public InsertAtMarker {
            requireOwnerAndTarget(owner, target);
            Objects.requireNonNull(marker, "marker");
            lines = List.copyOf(lines);
        }

        @Override
        public String operation() {
            return "insertAtMarker";
        }
    }

    /** Idempotent line append, for {@code .gitignore} and {@code .env.example}. */
    record AppendLines(RecipeId owner, String target, List<String> lines) implements PatchOp {

        public AppendLines {
            requireOwnerAndTarget(owner, target);
            lines = List.copyOf(lines);
        }

        @Override
        public String operation() {
            return "appendLines";
        }
    }

    /**
     * Declares an environment variable once and lands it in both places it has to exist: the {@code
     * .env.example} that documents it, and the compose service that consumes it. One op, because
     * two ops is how those two files drift apart.
     *
     * <p>{@code composeTarget} and {@code composeService} are null when containers were not
     * selected; the applier then writes only the example file.
     */
    record AddEnvVar(
            RecipeId owner,
            String target,
            String composeTarget,
            String composeService,
            String name,
            String value,
            String comment)
            implements PatchOp {

        public AddEnvVar {
            requireOwnerAndTarget(owner, target);
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String operation() {
            return "addEnvVar";
        }
    }

    /** A compose service with its healthcheck and {@code depends_on} wiring. */
    record AddComposeService(
            RecipeId owner, String target, String serviceName, Map<String, Object> definition, List<String> dependsOn)
            implements PatchOp {

        public AddComposeService {
            requireOwnerAndTarget(owner, target);
            Objects.requireNonNull(serviceName, "serviceName");
            definition = Ordered.copyOf(definition);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }

        @Override
        public String operation() {
            return "addComposeService";
        }
    }

    private static void requireOwnerAndTarget(RecipeId owner, String target) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(target, "target");
        if (target.isBlank()) {
            throw new IllegalArgumentException("patch target must not be blank");
        }
    }
}
