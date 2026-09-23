package dev.kitbash.core.error;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The fields every §14 error carries, factored out so each variant declares only what is peculiar
 * to it.
 *
 * <p>{@code hint} is a required constructor parameter and is rejected when blank. §14 says every
 * error names the next action; making that a requirement of the type is the only way it is still
 * true at the tenth error site.
 *
 * <p>{@code recipe}, {@code file} and {@code selectionHash} are nullable, because some errors
 * genuinely have no recipe — a bad identifier is the caller's, not a recipe's — and the hash is
 * only known once the selection has been parsed.
 */
public record ErrorDetail(Stage stage, String recipe, String file, String message, String hint, String selectionHash) {

    public ErrorDetail {
        Objects.requireNonNull(stage, "stage");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("every error must carry a message");
        }
        if (hint == null || hint.isBlank()) {
            throw new IllegalArgumentException("every error must carry a hint naming the next action (plan §14)");
        }
        rejectAnEmptyHint(hint);
    }

    /**
     * The bar §14 sets, which "not blank" does not reach.
     *
     * <p>Non-blank settles whether a hint exists. This settles the failure that actually happens:
     * a hint written to get past the constructor, which restates the message or says "try again
     * later" and sends the reader nowhere.
     *
     * <p>At construction rather than in a test, because {@code invalidIdentifier} takes its hint
     * from the caller — fourteen call sites today and more tomorrow — and a rule enforced by a
     * sample-based test only covers the samples somebody remembered to add.
     *
     * <p>Nothing here can prove a hint is <em>useful</em>. These are the shapes an unhelpful one
     * takes, and refusing them is what makes the useful ones the path of least resistance.
     */
    private static void rejectAnEmptyHint(String hint) {
        String normalised = hint.toLowerCase(Locale.ROOT);
        for (String empty : EMPTY_HINTS) {
            if (normalised.contains(empty)) {
                throw new IllegalArgumentException("'" + hint + "' is what a hint says when nobody thought about the "
                        + "next action (plan §14). Name what the reader should do instead.");
            }
        }
        if (hint.strip().length() < MINIMUM_HINT) {
            throw new IllegalArgumentException("'" + hint + "' is too short to name a next action (plan §14); "
                    + "a hint is a sentence, not a word.");
        }
    }

    /** A hint shorter than this has never named an action in practice. */
    private static final int MINIMUM_HINT = 20;

    private static final List<String> EMPTY_HINTS =
            List.of("try again later", "check your input", "something went wrong", "please retry", "contact support");

    public static ErrorDetail of(Stage stage, String message, String hint) {
        return new ErrorDetail(stage, null, null, message, hint, null);
    }

    public ErrorDetail withRecipe(String value) {
        return new ErrorDetail(stage, value, file, message, hint, selectionHash);
    }

    public ErrorDetail withFile(String value) {
        return new ErrorDetail(stage, recipe, value, message, hint, selectionHash);
    }

    public ErrorDetail withSelectionHash(String value) {
        return new ErrorDetail(stage, recipe, file, message, hint, value);
    }
}
