package dev.kitbash.core.error;

/**
 * The carrier that gets a {@link GenerationError} from wherever it was detected to whatever is
 * going to render it.
 *
 * <p>Unchecked on purpose: §6's stages are pure functions composed into a pipeline, and threading a
 * checked exception through every one of them would push error handling into places that have
 * nothing useful to do with it. The structured error is the payload; the exception is transport.
 */
public final class GenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient GenerationError error;

    public GenerationException(GenerationError error) {
        super(error.message());
        this.error = error;
    }

    public GenerationError error() {
        return error;
    }
}
