package dev.kitbash.core.error;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Every way generation can fail, as a closed hierarchy (§14).
 *
 * <p>Each variant carries the structured facts its message was built from, not only the prose. That
 * is what lets the wizard render a conflict inline on the offending field (§9) rather than dropping
 * a sentence into a banner, and what lets the CLI print a machine-parseable envelope for CI (§14).
 *
 * <p>Construct these through the static factories rather than the canonical constructors. The
 * factories are where the standard wording and the standard hint live, so two call sites reporting
 * the same failure cannot say it two different ways.
 */
public sealed interface GenerationError {

    /** The fields every error shares. */
    ErrorDetail detail();

    ErrorCode code();

    /** A copy stamped with the selection hash, which is only known once parsing has happened. */
    GenerationError withSelectionHash(String selectionHash);

    default Stage stage() {
        return detail().stage();
    }

    default String recipe() {
        return detail().recipe();
    }

    default String file() {
        return detail().file();
    }

    default String message() {
        return detail().message();
    }

    default String hint() {
        return detail().hint();
    }

    default String selectionHash() {
        return detail().selectionHash();
    }

    default GenerationException asException() {
        return new GenerationException(this);
    }

    // --- variants ------------------------------------------------------------

    record UnknownRecipe(ErrorDetail detail, String recipeId) implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.UNKNOWN_RECIPE;
        }

        @Override
        public UnknownRecipe withSelectionHash(String selectionHash) {
            return new UnknownRecipe(detail.withSelectionHash(selectionHash), recipeId);
        }
    }

    /** A {@code requires} that nothing in the selected set provides. */
    record CapabilityUnsatisfied(ErrorDetail detail, String capability, String requiredBy, String optionId)
            implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.CAPABILITY_UNSATISFIED;
        }

        @Override
        public CapabilityUnsatisfied withSelectionHash(String selectionHash) {
            return new CapabilityUnsatisfied(detail.withSelectionHash(selectionHash), capability, requiredBy, optionId);
        }
    }

    record Conflict(ErrorDetail detail, String left, String right, String optionId) implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.CONFLICT;
        }

        @Override
        public Conflict withSelectionHash(String selectionHash) {
            return new Conflict(detail.withSelectionHash(selectionHash), left, right, optionId);
        }
    }

    record Cycle(ErrorDetail detail, List<String> members) implements GenerationError {
        public Cycle {
            members = List.copyOf(members);
        }

        @Override
        public ErrorCode code() {
            return ErrorCode.CYCLE;
        }

        @Override
        public Cycle withSelectionHash(String selectionHash) {
            return new Cycle(detail.withSelectionHash(selectionHash), members);
        }
    }

    record PatchTargetMissing(ErrorDetail detail, String operation) implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.PATCH_TARGET_MISSING;
        }

        @Override
        public PatchTargetMissing withSelectionHash(String selectionHash) {
            return new PatchTargetMissing(detail.withSelectionHash(selectionHash), operation);
        }
    }

    record PatchCollision(ErrorDetail detail, String operation, String key, String otherRecipe)
            implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.PATCH_COLLISION;
        }

        @Override
        public PatchCollision withSelectionHash(String selectionHash) {
            return new PatchCollision(detail.withSelectionHash(selectionHash), operation, key, otherRecipe);
        }
    }

    record InvalidIdentifier(ErrorDetail detail, String field, String value, String rule) implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.INVALID_IDENTIFIER;
        }

        @Override
        public InvalidIdentifier withSelectionHash(String selectionHash) {
            return new InvalidIdentifier(detail.withSelectionHash(selectionHash), field, value, rule);
        }
    }

    record PathEscape(ErrorDetail detail, String path, String reason) implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.PATH_ESCAPE;
        }

        @Override
        public PathEscape withSelectionHash(String selectionHash) {
            return new PathEscape(detail.withSelectionHash(selectionHash), path, reason);
        }
    }

    record LimitExceeded(ErrorDetail detail, String cap, long limit, long observed) implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.LIMIT_EXCEEDED;
        }

        @Override
        public LimitExceeded withSelectionHash(String selectionHash) {
            return new LimitExceeded(detail.withSelectionHash(selectionHash), cap, limit, observed);
        }
    }

    record RenderFailed(ErrorDetail detail, String template, int line, String cause) implements GenerationError {
        @Override
        public ErrorCode code() {
            return ErrorCode.RENDER_FAILED;
        }

        @Override
        public RenderFailed withSelectionHash(String selectionHash) {
            return new RenderFailed(detail.withSelectionHash(selectionHash), template, line, cause);
        }
    }

    // --- factories -----------------------------------------------------------

    static UnknownRecipe unknownRecipe(String recipeId, Set<String> known) {
        Objects.requireNonNull(recipeId, "recipeId");
        return new UnknownRecipe(
                new ErrorDetail(
                        Stage.PARSE,
                        recipeId,
                        null,
                        "No recipe with id '" + recipeId + "' exists in this catalog.",
                        known.isEmpty()
                                ? "Fetch /api/v1/metadata to see the recipes this catalog offers."
                                : "Did you mean one of: " + nearest(recipeId, known) + "?",
                        null),
                recipeId);
    }

    /**
     * A selection that names nothing. Reported as an unsatisfied {@code project-root} because that
     * is literally what it is, and because the alternative — generating an empty zip — looks like
     * the generator worked.
     */
    static CapabilityUnsatisfied emptySelection() {
        return new CapabilityUnsatisfied(
                new ErrorDetail(
                        Stage.RESOLVE,
                        null,
                        null,
                        "This selection does not name anything to generate.",
                        "Choose at least a backend or a frontend. GET /api/v1/metadata lists what "
                                + "this catalog offers.",
                        null),
                "project-root",
                "the selection",
                "backend");
    }

    static CapabilityUnsatisfied capabilityUnsatisfied(String capability, String requiredBy, String optionId) {
        return new CapabilityUnsatisfied(
                new ErrorDetail(
                        Stage.RESOLVE,
                        requiredBy,
                        null,
                        requiredBy + " requires '" + capability + "' and nothing in this selection provides it.",
                        "Choose a value for '" + optionId + "'.",
                        null),
                capability,
                requiredBy,
                optionId);
    }

    static Conflict conflict(String left, String right, String optionId) {
        return new Conflict(
                new ErrorDetail(
                        Stage.RESOLVE,
                        left,
                        null,
                        left + " and " + right + " cannot be selected together.",
                        "Change '" + optionId + "' to pick one of them.",
                        null),
                left,
                right,
                optionId);
    }

    static Cycle cycle(List<String> members) {
        return new Cycle(
                new ErrorDetail(
                        Stage.RESOLVE,
                        members.isEmpty() ? null : members.get(0),
                        null,
                        "These recipes depend on each other in a cycle: " + String.join(" -> ", members) + ".",
                        "Break the cycle by removing one of the requires declarations in those manifests.",
                        null),
                members);
    }

    static PatchTargetMissing patchTargetMissing(String recipe, String file, String operation) {
        return new PatchTargetMissing(
                new ErrorDetail(
                        Stage.PATCH,
                        recipe,
                        file,
                        "Patch target was not produced by any selected recipe.",
                        recipe + " patches '" + file + "', so a recipe that produces that file has to be "
                                + "selected as well.",
                        null),
                operation);
    }

    static PatchCollision patchCollision(String recipe, String file, String operation, String key, String otherRecipe) {
        return new PatchCollision(
                new ErrorDetail(
                        Stage.PATCH,
                        recipe,
                        file,
                        "'" + key + "' is already set in " + file + " by " + otherRecipe + ".",
                        "Two recipes cannot own the same key: move one behind an option, or have the "
                                + "owning recipe expose a marker to insert at.",
                        null),
                operation,
                key,
                otherRecipe);
    }

    static InvalidIdentifier invalidIdentifier(String field, String value, String rule, String hint) {
        return new InvalidIdentifier(
                new ErrorDetail(Stage.PARSE, null, null, "'" + field + "' is not valid: " + rule, hint, null),
                field,
                value,
                rule);
    }

    static PathEscape pathEscape(String recipe, String path, String reason) {
        return new PathEscape(
                new ErrorDetail(
                        Stage.PLAN,
                        recipe,
                        path,
                        "Refusing to write '" + path + "': " + reason,
                        "Fix the templated path in " + recipe + "; it has to resolve inside the project root.",
                        null),
                path,
                reason);
    }

    static LimitExceeded limitExceeded(String cap, long limit, long observed) {
        return new LimitExceeded(
                new ErrorDetail(
                        Stage.PLAN,
                        null,
                        null,
                        "This selection exceeds the " + cap + " limit of " + limit + " (observed " + observed + ").",
                        "Deselect an option that contributes files, or raise the cap deliberately in core.",
                        null),
                cap,
                limit,
                observed);
    }

    static RenderFailed renderFailed(String recipe, String template, int line, String cause) {
        return new RenderFailed(
                new ErrorDetail(
                        Stage.RENDER,
                        recipe,
                        template,
                        "Template failed to render at line " + line + ": " + cause,
                        "Fix the template in " + recipe + ", or declare the variable it reads under "
                                + "variables.required in its manifest.",
                        null),
                template,
                line,
                cause);
    }

    /** Cheap "did you mean": the ids sharing the longest prefix, capped at three suggestions. */
    private static String nearest(String value, Set<String> known) {
        return known.stream()
                .sorted(Comparator.comparingInt((String candidate) -> -commonPrefix(value, candidate))
                        .thenComparing(Comparator.naturalOrder()))
                .limit(3)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static int commonPrefix(String left, String right) {
        int max = Math.min(left.length(), right.length());
        int index = 0;
        while (index < max && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }
}
