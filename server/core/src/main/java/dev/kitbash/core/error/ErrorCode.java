package dev.kitbash.core.error;

/**
 * The closed set of things that can go wrong, exactly as §14 enumerates them.
 *
 * <p>Typed in {@code core}, not assembled as strings at the controller: a client that wants to
 * behave differently for a conflict than for a breached cap needs a stable symbol, and a message is
 * not one.
 */
public enum ErrorCode {
    UNKNOWN_RECIPE,
    CAPABILITY_UNSATISFIED,
    CONFLICT,
    CYCLE,
    PATCH_TARGET_MISSING,
    PATCH_COLLISION,
    INVALID_IDENTIFIER,
    PATH_ESCAPE,
    LIMIT_EXCEEDED,
    RENDER_FAILED
}
