package dev.kitbash.core.resolve;

import java.util.Objects;

/**
 * Something the caller should know about but which does not stop a generation.
 *
 * <p>The distinction from a conflict is load-bearing for the wizard: a conflict blocks Generate and
 * renders inline on the field that caused it (§9), while a warning is information in the right rail.
 * Collapsing the two would mean either blocking on things that are fine, or hiding things that are
 * not — both of which teach users to ignore the panel.
 *
 * <p>{@code optionId} is nullable but supplied whenever there is an option the user could change:
 * §8 asks diagnostics to name the control, not the machinery behind it.
 */
public record ResolutionWarning(String optionId, String message, String hint) {

    public ResolutionWarning {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(hint, "hint");
    }

    public static ResolutionWarning of(String optionId, String message, String hint) {
        return new ResolutionWarning(optionId, message, hint);
    }
}
