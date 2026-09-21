package dev.kitbash.verify.bump;

/**
 * One version that moved.
 *
 * @param tracked what the recipe pins today
 * @param to the release the job proposes
 * @param files how many files inside the recipe carried the old literal
 */
public record Bump(TrackedVersion tracked, String to, int files) {

    /** The line a reviewer reads, and the only summary the pull request needs per bump. */
    public String describe() {
        return "%s: %s %s → %s (%d file%s)"
                .formatted(tracked.recipe(), tracked.describe(), tracked.version(), to, files, files == 1 ? "" : "s");
    }
}
