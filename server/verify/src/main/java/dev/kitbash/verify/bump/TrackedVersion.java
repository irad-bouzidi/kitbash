package dev.kitbash.verify.bump;

/**
 * One version a recipe pins, and the artifact it follows.
 *
 * <p>Declared in the recipe's manifest rather than discovered by pattern, because a job that
 * guesses which literals are versions eventually rewrites a port number — and it would do it in a
 * pull request that looks exactly like the twenty good ones before it.
 *
 * @param recipe the recipe directory the literal lives in, which bounds the replacement
 * @param version the exact string the recipe writes today
 * @param artifact the Maven coordinate whose latest release is compared against
 * @param label what a person calls it, for the bump's own summary
 * @param holdBelow a ceiling the job will not cross, or null
 * @param because why that ceiling is there, so the next person can tell whether it still applies
 */
public record TrackedVersion(
        String recipe, String version, String artifact, String label, String holdBelow, String because) {

    /**
     * Whether a candidate is above a declared ceiling.
     *
     * <p>The first bump this job ever produced was red because ktlint 1.8 needs a newer Kotlin
     * compiler than the Spotless we use ships. Without a ceiling the job would propose it again
     * every Monday, and a job that is red every Monday gets switched off — so a known
     * incompatibility is recorded in the manifest, with its reason, rather than in somebody's
     * memory.
     */
    public boolean isHeld(String candidate) {
        return holdBelow != null && !Versions.isNewer(holdBelow, candidate);
    }

    public String group() {
        return artifact.substring(0, artifact.indexOf(':'));
    }

    public String name() {
        return artifact.substring(artifact.indexOf(':') + 1);
    }

    public String describe() {
        return label == null || label.isBlank() ? artifact : label;
    }
}
