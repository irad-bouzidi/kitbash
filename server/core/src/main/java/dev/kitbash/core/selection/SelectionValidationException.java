package dev.kitbash.core.selection;

/** A selection that cannot be generated from, with a message aimed at the person who sent it. */
public class SelectionValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public SelectionValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
