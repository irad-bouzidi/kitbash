package dev.kitbash.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §14 requires every failure to name the stage, the recipe and the file, and to carry a hint that
 * names the next action. These tests hold that requirement to the type rather than to reviewers.
 */
class GenerationErrorTest {

    /** The same exhaustive-switch shape the error renderers use. No default branch. */
    private static String render(GenerationError error) {
        return switch (error) {
            case GenerationError.UnknownRecipe value -> value.recipeId();
            case GenerationError.CapabilityUnsatisfied value -> value.capability();
            case GenerationError.Conflict value -> value.left() + "/" + value.right();
            case GenerationError.Cycle value -> String.join("->", value.members());
            case GenerationError.PatchTargetMissing value -> value.operation();
            case GenerationError.PatchCollision value -> value.key();
            case GenerationError.InvalidIdentifier value -> value.field();
            case GenerationError.PathEscape value -> value.path();
            case GenerationError.LimitExceeded value -> value.cap();
            case GenerationError.RenderFailed value -> value.template();
        };
    }

    private static List<GenerationError> oneOfEach() {
        return List.of(
                GenerationError.unknownRecipe("backend-spring-jva", Set.of("backend-spring-java")),
                GenerationError.capabilityUnsatisfied("rest-api", "frontend-react-vite", "backend"),
                // A second CapabilityUnsatisfied, because `emptySelection` is a distinct factory a
                // user reaches by pressing Generate with nothing chosen — and kitbash-39 found it
                // was the one reachable envelope no test had ever looked at.
                GenerationError.emptySelection(),
                GenerationError.conflict("backend-spring-java", "backend-spring-kotlin", "backend"),
                GenerationError.cycle(List.of("a", "b", "a")),
                GenerationError.patchTargetMissing(
                        "feature-auth-jwt", "backend/src/main/resources/application.yml", "mergeYaml"),
                GenerationError.patchCollision(
                        "feature-auth-jwt", "application.yml", "mergeYaml", "spring.security", "base"),
                GenerationError.invalidIdentifier(
                        "packageName",
                        "com.new.thing",
                        "'new' is a Java keyword",
                        "Rename the segment: 'com.newthing' or 'com.acme.thing' both work."),
                GenerationError.pathEscape("base", "../../etc/passwd", "resolves outside the project root"),
                GenerationError.limitExceeded("file count", 5000, 5001),
                GenerationError.renderFailed("base", "files/README.md.peb", 12, "unknown variable 'projetName'"));
    }

    @Test
    @DisplayName("every §14 code has a variant, and the renderer switch needs no default branch")
    void everyCodeHasAVariant() {
        // A set, not a list: `emptySelection` and `capabilityUnsatisfied` are two factories a user
        // reaches by two different routes and they share a code, so "one sample per code" was never
        // the rule — "no code without a sample" is.
        assertThat(oneOfEach()).map(GenerationError::code).containsAll(java.util.Set.of(ErrorCode.values()));
        assertThat(oneOfEach()).map(GenerationErrorTest::render).doesNotContainNull();
        assertThat(GenerationError.class.getPermittedSubclasses()).hasSize(ErrorCode.values().length);
    }

    @Test
    @DisplayName("every error names the next action, because §14 says a hint is not optional")
    void everyErrorCarriesAHint() {
        assertThat(oneOfEach()).allSatisfy(error -> {
            assertThat(error.message()).isNotBlank();
            assertThat(error.hint()).isNotBlank();
            assertThat(error.stage()).isNotNull();
        });
    }

    /**
     * The bar is the type's, so this checks the type refuses rather than that the samples pass.
     *
     * <p>{@code hintIsRequiredByTheType} settles whether a hint exists. This settles whether it
     * says anything — and it is asserted against {@link ErrorDetail} rather than against the
     * factories because {@code invalidIdentifier} takes its hint from the caller, at fourteen sites
     * and counting. A sample-based check would only ever cover the samples somebody remembered.
     */
    @Test
    @DisplayName("a hint that names no action is refused at construction, not reviewed for later")
    void anEmptyHintIsRefused() {
        assertThatThrownBy(() -> ErrorDetail.of(Stage.PLAN, "something broke", "Try again later."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("next action");

        assertThatThrownBy(() -> ErrorDetail.of(Stage.PLAN, "something broke", "Check your input and retry."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("next action");

        assertThatThrownBy(() -> ErrorDetail.of(Stage.PLAN, "something broke", "Rename it."))
                .as("a hint is a sentence, not a word")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");

        assertThat(ErrorDetail.of(Stage.PLAN, "something broke", "Rename the segment: 'com.newthing' works.")
                        .hint())
                .isNotBlank();
    }

    /**
     * §39 is absolute: no user-facing stack traces, ever.
     *
     * <p>The way one gets in is never deliberate — a factory concatenating {@code e.getMessage()}
     * from a cause that was itself an exception's {@code toString()}. These are the marks that
     * leaves.
     */
    @Test
    @DisplayName("nothing a user reads carries a stack trace or a Java class name")
    void noStackTracesInTheEnvelope() {
        assertThat(oneOfEach()).allSatisfy(error -> assertThat(error.message() + " " + error.hint())
                .as("%s", error.code())
                .doesNotContain("java.lang.")
                .doesNotContain("\tat ")
                .doesNotContain("Exception:"));
    }

    @Test
    @DisplayName("an error without a hint cannot be constructed at all")
    void hintIsRequiredByTheType() {
        assertThatThrownBy(() -> new ErrorDetail(Stage.PLAN, null, null, "something broke", "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hint");
    }

    @Test
    @DisplayName("the selection hash is stamped on afterwards, since parsing is what produces it")
    void selectionHashIsStampedLater() {
        GenerationError stamped =
                GenerationError.limitExceeded("file count", 5000, 5001).withSelectionHash("9f2c");

        assertThat(stamped.selectionHash()).isEqualTo("9f2c");
        assertThat(stamped.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("PATCH_TARGET_MISSING reproduces the §14 envelope, hint included")
    void patchTargetMissingMatchesThePlan() {
        GenerationError error = GenerationError.patchTargetMissing(
                        "feature-auth-jwt", "backend/src/main/resources/application.yml", "mergeYaml")
                .withSelectionHash("9f2c");

        assertThat(error.code()).isEqualTo(ErrorCode.PATCH_TARGET_MISSING);
        assertThat(error.stage()).isEqualTo(Stage.PATCH);
        assertThat(error.recipe()).isEqualTo("feature-auth-jwt");
        assertThat(error.file()).isEqualTo("backend/src/main/resources/application.yml");
        assertThat(error.message()).isEqualTo("Patch target was not produced by any selected recipe.");
        assertThat(error.hint()).contains("feature-auth-jwt").contains("has to be selected");
        assertThat(error.selectionHash()).isEqualTo("9f2c");
    }

    @Test
    @DisplayName("a mistyped recipe id is answered with the ids it was probably meant to be")
    void unknownRecipeSuggests() {
        GenerationError error = GenerationError.unknownRecipe(
                "backend-spring-jva", Set.of("backend-spring-java", "frontend-react-vite", "base"));

        assertThat(error.hint()).contains("backend-spring-java");
    }

    @Test
    @DisplayName("a cycle names its members, because 'cycle detected' is not a diagnosis")
    void cycleNamesItsMembers() {
        assertThat(GenerationError.cycle(List.of("feature-a", "feature-b", "feature-a"))
                        .message())
                .contains("feature-a -> feature-b -> feature-a");
    }

    @Test
    @DisplayName("a limit breach reports the observed value next to the cap")
    void limitExceededReportsObserved() {
        assertThat(GenerationError.limitExceeded("uncompressed size", 52_428_800L, 60_000_000L)
                        .message())
                .contains("52428800")
                .contains("60000000");
    }

    @Test
    @DisplayName("the exception is transport; the structured error is the payload")
    void exceptionCarriesTheError() {
        GenerationError error = GenerationError.limitExceeded("file count", 5000, 5001);

        GenerationException exception = error.asException();

        assertThat(exception.error()).isSameAs(error);
        assertThat(exception).hasMessage(error.message());
    }
}
