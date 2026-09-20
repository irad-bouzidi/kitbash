package dev.kitbash.core.selection;

import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.util.WindowsNames;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The allowlist every user-supplied identifier has to satisfy before it reaches a template (§13).
 *
 * <p>This is the first of the two layers. Nothing here has been rendered yet, so a value that fails
 * never becomes part of a file name, a package declaration or a Gradle coordinate — where the
 * failure would surface as a confusing compile error in the generated project rather than as a 400.
 * {@link dev.kitbash.core.plan.SafePaths} is the second layer, and it defends against recipes
 * rather than users.
 *
 * <p><b>Reject, never sanitize.</b> §13 is explicit about this and it reads like a usability
 * decision, but it is a correctness one: stripping the offending characters means the user asked
 * for one package name and silently got another, and found out at compile time. Every rejection
 * carries the offending value, the rule it broke and a hint.
 */
public final class Identifiers {

    /**
     * §13's rule, with one tightening: the first character must be a letter.
     *
     * <p>A name is used as an artifact id and as a directory name, and a leading digit is invalid
     * in enough of the places it lands — a Kotlin package segment, some npm scopes — that allowing
     * it here only moves the failure later. Trailing hyphens go for the same reason.
     */
    private static final Pattern PROJECT_NAME = Pattern.compile("[a-z]([a-z0-9-]{0,62}[a-z0-9])?");

    private static final Pattern DOTTED_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Set<String> SUPPORTED_JAVA_VERSIONS = Set.of("17", "21", "25");

    /**
     * A package name long enough to be nonsense is still a package name the regex accepts, and it
     * becomes a directory path segment by segment. Both bounds are generous by two orders of
     * magnitude against anything real, and exist so that nothing user-supplied is unbounded.
     */
    private static final int MAX_DOTTED_LENGTH = 256;

    private static final int MAX_SEGMENT_LENGTH = 64;

    /** Generous for a label or a description, far below anything that could exhaust memory. */
    private static final int MAX_VALUE_LENGTH = 1_024;

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

    /**
     * Kotlin's hard keywords, rejected now although the Kotlin backend is phase 3.
     *
     * <p>§13 asks for this deliberately: a package name accepted today ends up in someone's
     * history, their preset and their share link, and tightening the rule later would turn those
     * into 400s. The ones Java does not already forbid are what matter — {@code fun}, {@code val},
     * {@code var}, {@code object}, {@code when}, {@code is}, {@code in}, {@code typealias}.
     */
    private static final Set<String> KOTLIN_KEYWORDS = Set.of(
            "as",
            "break",
            "class",
            "continue",
            "do",
            "else",
            "false",
            "for",
            "fun",
            "if",
            "in",
            "interface",
            "is",
            "null",
            "object",
            "package",
            "return",
            "super",
            "this",
            "throw",
            "true",
            "try",
            "typealias",
            "typeof",
            "val",
            "var",
            "when",
            "while");

    private Identifiers() {}

    public static String requireProjectName(String value) {
        require(
                value != null && PROJECT_NAME.matcher(value).matches(),
                "projectName",
                value,
                "must be 1-64 characters of lowercase letters, digits and hyphens, starting with a "
                        + "letter and ending with a letter or digit",
                "It becomes the project directory, the artifact id and part of several file names, so it "
                        + "has to be legal in all of them. Try 'order-service'.");
        // The name becomes the project's directory, and Windows will not create a directory called
        // CON whatever the regex thinks of it.
        require(
                !WindowsNames.isDeviceName(value),
                "projectName",
                value,
                "must not be a name Windows reserves for a device",
                "The zip is extracted on Windows too, where a folder by this name cannot be created. "
                        + "Try 'con-service'.");
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
                value,
                "must be one of 17, 21 or 25",
                "These are the versions the recipes' toolchains and base images are built for.");
        return value;
    }

    /**
     * The floor under every variable, including one no manifest declares.
     *
     * <p>A catalog pattern is the real rule; this is what is left when there is none. It is
     * deliberately weak — a variable can legitimately be a sentence, a URL or a person's name — and
     * stops only the two things no value may be: unbounded, and full of control characters that
     * would rewrite the syntax of whatever file it lands in.
     */
    public static String requireWritableValue(String field, String value) {
        require(value != null, field, null, "must have a value", "Omit the variable rather than sending null.");
        require(
                value.length() <= MAX_VALUE_LENGTH,
                field,
                value,
                "must be at most " + MAX_VALUE_LENGTH + " characters",
                "This is written into a generated file; nothing legitimate is this long.");
        for (char character : value.toCharArray()) {
            // Tab is a real character in a real value; the rest are not.
            require(
                    character >= 0x20 || character == '\t',
                    field,
                    value,
                    "must not contain control characters",
                    "A newline or a NUL in a value rewrites the syntax of the file it lands in.");
        }
        return value;
    }

    private static void requireDotted(String value, String field) {
        require(
                value != null && DOTTED_IDENTIFIER.matcher(value).matches(),
                field,
                value,
                "must be dot-separated lowercase segments, each starting with a letter",
                "This is written into package declarations and directory names, where it has to be a "
                        + "legal identifier. Try 'com.acme.orders'.");
        require(
                value.length() <= MAX_DOTTED_LENGTH,
                field,
                value,
                "must be at most " + MAX_DOTTED_LENGTH + " characters",
                "It becomes a directory path, one segment per level, and no real package is this long.");
        for (String segment : value.split("\\.")) {
            require(
                    segment.length() <= MAX_SEGMENT_LENGTH,
                    field,
                    value,
                    "must not contain a segment longer than " + MAX_SEGMENT_LENGTH + " characters",
                    "Each segment becomes a directory name, which filesystems bound.");
            require(
                    !JAVA_KEYWORDS.contains(segment),
                    field,
                    value,
                    "must not use the Java keyword '" + segment + "' as a segment",
                    "javac would refuse the generated source outright. Rename the segment.");
            require(
                    !KOTLIN_KEYWORDS.contains(segment),
                    field,
                    value,
                    "must not use the Kotlin keyword '" + segment + "' as a segment",
                    "The Kotlin backend arrives in phase 3 and would refuse this package then. Rejecting "
                            + "it now costs a rename; accepting it would cost a broken saved preset later.");
        }
    }

    /**
     * Every rejection is the §14 envelope: the code, the field, the offending value, the rule it
     * broke and a hint. Assembling that here rather than at a controller is what keeps the error
     * vocabulary in {@code core}, where §14 puts it.
     */
    private static void require(boolean condition, String field, String value, String rule, String hint) {
        if (!condition) {
            throw GenerationError.invalidIdentifier(field, display(value), rule, hint)
                    .asException();
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
