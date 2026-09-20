package dev.kitbash.core.error;

import java.util.Locale;

/**
 * Which of the seven §6 stages an error came out of.
 *
 * <p>It is on the envelope because "generation failed" is useless, while "generation failed while
 * patching {@code application.yml} for {@code feature-auth-jwt}" is actionable (§14).
 */
public enum Stage {
    PARSE,
    RESOLVE,
    PLAN,
    RENDER,
    PATCH,
    POST_PROCESS,
    PACKAGE;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
