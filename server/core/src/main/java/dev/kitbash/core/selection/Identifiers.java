package dev.kitbash.core.selection;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The floor below which nothing is written to disk at all.
 *
 * <p>These are inline checks, not the full defence: the allowlist, the path-traversal rules and the
 * size caps are kitbash-20. What is here stops a request from putting nonsense into a file name, a
 * package declaration or a Gradle coordinate, where the failure would surface as a confusing
 * compile error in the generated project rather than as a 400.
 */
public final class Identifiers {

    private static final Pattern PROJECT_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?");
    private static final Pattern DOTTED_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Set<String> SUPPORTED_JAVA_VERSIONS = Set.of("17", "21", "25");

    /** Reserved words cannot appear as a package segment; javac rejects the generated source. */
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "_");

    private Identifiers() {}

    public static String requireProjectName(String value) {
        require(
                value != null && PROJECT_NAME.matcher(value).matches(),
                "projectName",
                "projectName must be 1-64 characters of lowercase letters, digits and hyphens, "
                        + "starting and ending with a letter or digit (got: " + display(value) + ")");
        return value;
    }

    public static String requireGroupId(String value) {
        requireDotted(value, "groupId");
        return value;
    }

    public static String requirePackageName(String value) {
        requireDotted(value, "packageName");
        return value;
    }

    public static String requireJavaVersion(String value) {
        require(
                value != null && SUPPORTED_JAVA_VERSIONS.contains(value),
                "javaVersion",
                "javaVersion must be one of 17, 21, 25 (got: " + display(value) + ")");
        return value;
    }

    private static void requireDotted(String value, String field) {
        require(
                value != null && DOTTED_IDENTIFIER.matcher(value).matches(),
                field,
                field + " must be dot-separated lowercase segments, each starting with a letter " + "(got: "
                        + display(value) + ")");
        for (String segment : value.split("\\.")) {
            require(
                    !JAVA_KEYWORDS.contains(segment),
                    field,
                    field + " segment '" + segment + "' is a Java keyword and cannot be part of a package name");
        }
    }

    private static void require(boolean condition, String field, String message) {
        if (!condition) {
            throw new SelectionValidationException(field, message);
        }
    }

    private static String display(String value) {
        if (value == null) {
            return "null";
        }
        String trimmed = value.length() > 80 ? value.substring(0, 80) + "…" : value;
        return "'" + trimmed.replaceAll("[\\p{Cntrl}]", "?") + "'";
    }
}
