package dev.kitbash.api.error;

/**
 * A preset, share link or generation that is not there (§14, kitbash-39).
 *
 * <p>Its own type because §39's audit found these three answering <b>400 with no error code and no
 * hint</b> — through {@code SelectionValidationException}, which exists for a malformed
 * <em>selection</em> and was standing in for a missing <em>resource</em>. Neither half of that was
 * right: the status said the caller sent something invalid when they had sent a perfectly good id
 * for a row that no longer exists, and the envelope carried none of what §14 requires.
 *
 * <p>Not a {@link dev.kitbash.core.error.GenerationError}, though, and deliberately so. Those carry
 * a §6 stage, and "no preset with that id" happens before any stage — forcing one on it would put a
 * lie in the envelope to satisfy a type.
 */
public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String resource;
    private final transient String hint;

    public ResourceNotFoundException(String resource, String message, String hint) {
        super(message);
        this.resource = resource;
        this.hint = hint;
    }

    public String resource() {
        return resource;
    }

    /** §14's rule applies here too: the envelope names the next action, not only the absence. */
    public String hint() {
        return hint;
    }
}
