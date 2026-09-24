package dev.kitbash.sandbox;

import java.util.Map;

/**
 * What crosses the process boundary, and deliberately nothing else (kitbash-47 §6.4).
 *
 * <p>Two maps in, one map out, as JSON over the worker's stdin and stdout. The shape is the
 * confinement as much as the namespace is: a worker that is handed template <i>sources</i> and
 * returns rendered <i>strings</i> never needs to open a file, so "read-only filesystem" is
 * achieved by there being nothing to read rather than by a mount option somebody has to remember
 * to set.
 *
 * <p>Pipes rather than a shared directory for the same reason. A directory would be a path the
 * parent has to create, permission, clean up and reason about concurrently, and every one of those
 * is a way for two renders to meet — which is threat 3.3. A pipe cannot be traversed.
 *
 * <p>The variable map holds primitives only, exactly as §13 requires of the in-process engine.
 * That is not merely preserved across the boundary, it is enforced by it: JSON cannot carry a live
 * object, so the worker could not receive one if the parent tried to send it.
 */
final class SandboxProtocol {

    private SandboxProtocol() {}

    /**
     * @param templates template name to source
     * @param variables the §13 primitive map: strings, booleans, numbers, lists of strings
     * @param recipeId the id rendered into {@code recipeId}, and named in any failure
     */
    record Request(Map<String, String> templates, Map<String, Object> variables, String recipeId) {}

    /**
     * @param files template name to rendered output, present only on success
     * @param error what went wrong, present only on failure — a message the parent may show,
     *     never a stack trace (§14: no stack traces reach a user)
     */
    record Response(Map<String, String> files, String error) {}
}
