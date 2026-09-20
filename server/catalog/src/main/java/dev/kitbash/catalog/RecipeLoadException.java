package dev.kitbash.catalog;

/**
 * A recipe tree that will not load, reported the way §7 requires: naming the file, the field and
 * the fix.
 *
 * <p>The loader is the only gate between a malformed recipe and a broken user download, so it fails
 * at boot rather than at render time — and a startup failure is only useful if it says what to
 * edit. "Invalid manifest" costs whoever is on call twenty minutes; the three fields below cost
 * them nothing.
 */
public class RecipeLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String file;
    private final String field;
    private final String fix;

    public RecipeLoadException(String file, String field, String problem, String fix) {
        super(format(file, field, problem, fix));
        this.file = file;
        this.field = field;
        this.fix = fix;
    }

    public RecipeLoadException(String file, String field, String problem, String fix, Throwable cause) {
        super(format(file, field, problem, fix), cause);
        this.file = file;
        this.field = field;
        this.fix = fix;
    }

    private static String format(String file, String field, String problem, String fix) {
        StringBuilder message = new StringBuilder(file);
        if (field != null && !field.isBlank()) {
            message.append(" [").append(field).append(']');
        }
        message.append(": ").append(problem);
        if (fix != null && !fix.isBlank()) {
            message.append(System.lineSeparator()).append("  Fix: ").append(fix);
        }
        return message.toString();
    }

    public String file() {
        return file;
    }

    public String field() {
        return field;
    }

    public String fix() {
        return fix;
    }
}
