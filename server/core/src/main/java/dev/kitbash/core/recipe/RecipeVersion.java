package dev.kitbash.core.recipe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A recipe's own semver, independent of the framework version it emits (§4, §7).
 *
 * <p>The distinction is the point: a recipe can gain an option or fix a patch without Spring Boot
 * moving, and Spring Boot can move without the recipe's contract changing. A pin references this
 * version ({@code backend-spring-java@1.4.0}); the framework version is display text.
 *
 * <p>Comparable, so the loader can reject a downgrade and two locks can be diffed. Pre-release and
 * build metadata are deliberately unsupported: a catalog shipped from git has no use for them, and
 * every use of them is another ordering rule to get wrong.
 */
public record RecipeVersion(int major, int minor, int patch) implements Comparable<RecipeVersion> {

    private static final Pattern PATTERN = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");

    public RecipeVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must not be negative");
        }
    }

    public static RecipeVersion parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "version must be MAJOR.MINOR.PATCH with no leading zeroes (got: '" + value + "')");
        }
        return new RecipeVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    @Override
    public int compareTo(RecipeVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
