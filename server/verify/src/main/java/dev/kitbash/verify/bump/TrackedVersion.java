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
 */
public record TrackedVersion(String recipe, String version, String artifact, String label) {

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
