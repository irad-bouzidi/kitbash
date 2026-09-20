package dev.kitbash.core.error;

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
    }

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
