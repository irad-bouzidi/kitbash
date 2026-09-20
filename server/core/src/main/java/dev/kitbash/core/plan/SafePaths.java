package dev.kitbash.core.plan;

import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.util.WindowsNames;

/**
 * Every path the generator writes, checked before anything is written (§13).
 *
 * <p>Two layers use this, on purpose. The first is the user's input, which never reaches a
 * template; the second is the rendered path, which defends against a <i>recipe</i> rather than a
 * user — {@code {{ packageName | packagePath }}/Thing.java} is a path a template computed, and a
 * recipe is code somebody other than the user wrote.
 *
 * <p>It rejects; it never sanitizes. §13 is explicit about that, and it looks like a usability
 * decision but is a correctness one: silently rewriting a path means the user asked for one file
 * and got another, and finds out when an import does not resolve.
 *
 * <p>The Windows rules are not hypothetical politeness. A zip is extracted wherever the person who
 * downloaded it works, and a path this generator is happy with can be unextractable there — which
 * is a broken project produced by a green pipeline.
 */
public final class SafePaths {

    /** The conventional per-component limit; every filesystem the output lands on has at least this. */
    private static final int MAX_SEGMENT_LENGTH = 255;

    /** Long enough for any real project layout, short enough that Windows' own limit is not hit. */
    private static final int MAX_PATH_LENGTH = 1_000;

    private SafePaths() {}

    /**
     * A path that still carries its {@code {{ … }}} placeholders: structure only.
     *
     * <p>This is the plan-stage layer. It cannot judge the characters, because a template path is
     * legitimately full of them — {@code {{ packageName | packagePath }}/Thing.java} contains a
     * pipe, which no file name may — so it checks what is already decided: that the path is
     * relative, that it traverses nowhere, and that it is not absurdly long. The rest is checked
     * once the placeholders are gone and the answer is knowable.
     */
    public static String requireTemplatePath(String recipe, String path) {
        return check(recipe, path, false);
    }

    /**
     * The path as it will be written, or a {@code PATH_ESCAPE} naming what is wrong with it.
     *
     * <p>This is the second layer, and the one that defends against a recipe rather than a user: by
     * here the placeholders are gone, so what is being judged is what a template actually produced.
     *
     * @param recipe the recipe the path came from, so the error names who to fix
     */
    public static String require(String recipe, String path) {
        return check(recipe, path, true);
    }

    private static String check(String recipe, String path, boolean rendered) {
        if (path == null || path.isBlank()) {
            throw refuse(recipe, String.valueOf(path), "a path must not be empty");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw refuse(recipe, path, "a path must be at most " + MAX_PATH_LENGTH + " characters");
        }
        if (path.startsWith("/")) {
            throw refuse(recipe, path, "a path must be relative to the project root, not absolute");
        }
        if (path.contains("\\")) {
            throw refuse(
                    recipe,
                    path,
                    "a path must be /-separated; a backslash is a separator on Windows "
                            + "and a legal file-name character elsewhere, so it can mean two different trees");
        }
        if (path.length() > 1 && path.charAt(1) == ':') {
            throw refuse(recipe, path, "a path must not carry a drive letter");
        }
        if (path.endsWith("/")) {
            throw refuse(recipe, path, "a path must name a file, not a directory");
        }

        for (String segment : path.split("/", -1)) {
            requireSegment(recipe, path, segment, rendered);
        }
        return path;
    }

    private static void requireSegment(String recipe, String path, String segment, boolean rendered) {
        if (segment.isEmpty()) {
            throw refuse(recipe, path, "a path must not contain an empty segment");
        }
        if (segment.equals(".") || segment.equals("..")) {
            throw refuse(recipe, path, "a path must not traverse: '" + segment + "' escapes the project root");
        }
        if (segment.length() > MAX_SEGMENT_LENGTH) {
            throw refuse(
                    recipe,
                    path,
                    "'" + segment + "' is longer than the " + MAX_SEGMENT_LENGTH + " characters a file name can be");
        }
        if (!rendered) {
            return;
        }
        for (char character : segment.toCharArray()) {
            if (character < 0x20 || character == 0x7f) {
                throw refuse(recipe, path, "a path must not contain control characters");
            }
            if (WindowsNames.FORBIDDEN_CHARACTERS.indexOf(character) >= 0) {
                throw refuse(
                        recipe,
                        path,
                        "'" + character + "' cannot appear in a file name on Windows, "
                                + "where this zip will also be extracted");
            }
        }
        // Windows silently strips a trailing dot or space, which turns two distinct paths into one
        // and makes an extracted project differ from the one that was generated.
        if (segment.endsWith(".") || segment.endsWith(" ")) {
            throw refuse(recipe, path, "'" + segment + "' ends with a dot or a space, which Windows strips");
        }
        if (WindowsNames.isDeviceName(segment)) {
            throw refuse(
                    recipe,
                    path,
                    "'" + segment + "' is a reserved device name on Windows, "
                            + "which refuses to create it with or without an extension");
        }
    }

    /**
     * Two paths that differ only by case.
     *
     * <p>They coexist happily on Linux and collide on macOS and Windows, where the second one
     * silently replaces the first. The generator's own tests would never notice; the user's
     * project would be missing a file, and the recipe that lost the race would look innocent.
     */
    public static GenerationException refuseCaseCollision(String recipe, String path, String existing) {
        return refuse(
                recipe,
                path,
                "'" + existing + "' is already in the project and differs only by case; on macOS and "
                        + "Windows one would silently replace the other");
    }

    private static GenerationException refuse(String recipe, String path, String reason) {
        return GenerationError.pathEscape(recipe, path, reason).asException();
    }
}
