package dev.kitbash.api.store;

import java.util.Locale;

/**
 * Where a submission is in its review (§47).
 *
 * <p>The order is the workflow and the workflow is the control: §47 requires a contributed recipe
 * to be <b>invisible until approved</b>, and to go through the verification matrix <b>before</b>
 * approval rather than after. So {@code VERIFIED} is a state rather than a flag on {@code
 * APPROVED} — a reviewer should be unable to approve something that has not built, and a status
 * that conflated the two would let them.
 */
public enum ContributedRecipeStatus {
    /** Submitted and not yet built. Visible to reviewers, to nobody else, and to no generation. */
    SUBMITTED,
    /** The matrix passed. Ready for a human, and still invisible. */
    VERIFIED,
    /** A named reviewer approved it. The only state in which it reaches the catalog. */
    APPROVED,
    /** Withdrawn. Out of the catalog, and every generation that used it is flagged. */
    REVOKED;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ContributedRecipeStatus of(String wireName) {
        return valueOf(wireName.toUpperCase(Locale.ROOT));
    }

    /** Whether the catalog may show it. One place, because §47's rule is one sentence. */
    public boolean visible() {
        return this == APPROVED;
    }
}
