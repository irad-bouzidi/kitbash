package dev.kitbash.api.verify;

/**
 * The queue is as long as it is allowed to get (§12, §14).
 *
 * <p>It carries the depth because §14 wants a hint that names the next action, and the next action
 * here depends on a number the caller cannot see. "Try again later" is not an action; "there are
 * thirty-two runs ahead of you" lets someone decide whether to wait or to come back after lunch.
 *
 * <p>Refusing is the point. Accepting the request and queueing it for an hour would be politer and
 * worse: the caller would sit on a spinner with no way to learn that nothing was happening.
 */
public class QueueFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient int depth;

    public QueueFullException(int depth) {
        super("The verification queue is full: " + depth + " waiting");
        this.depth = depth;
    }

    public int depth() {
        return depth;
    }
}
