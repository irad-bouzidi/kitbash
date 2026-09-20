package dev.kitbash.core.hostile;

import java.util.List;

/**
 * The corpus every layer that takes user input is tested against (§13).
 *
 * <p>It is a fixture rather than a list inside one test class because three tasks want it:
 * {@code kitbash-20} makes the generator reject these, {@code kitbash-39} asserts each rejection
 * arrives as a structured error, and {@code kitbash-47} re-aims the whole set at uploaded recipes.
 * A corpus that has to be copied to be reused is a corpus that drifts, and the copy that drifts is
 * always the one guarding the newer surface.
 *
 * <p>Each entry carries <i>why</i> it is here. A corpus of bare strings is one somebody prunes
 * during a refactor because an entry looked redundant, and the entry that looked redundant was the
 * one covering the case nobody had thought about twice.
 */
public final class HostileInputs {

    private HostileInputs() {}

    /** One hostile value and the reason it must be refused. */
    public record Case(String value, String why) {

        @Override
        public String toString() {
            // Surfaces in the parameterised test's name, so a failure says which case failed.
            return why + " — " + display();
        }

        /** Control characters printed as escapes, so a failure message is not itself unreadable. */
        public String display() {
            StringBuilder out = new StringBuilder("\"");
            for (char character : value.toCharArray()) {
                out.append(character < 0x20 || character == 0x7f ? "\\u%04x".formatted((int) character) : character);
            }
            return out.append('"').toString();
        }
    }

    /**
     * Values that must never be accepted as an identifier — a package name, a group id or a
     * project name.
     */
    public static List<Case> identifiers() {
        return List.of(
                new Case("", "empty"),
                new Case(" ", "whitespace only"),
                new Case("com.new.thing", "a Java keyword as a package segment"),
                new Case("com.fun.thing", "a Kotlin keyword as a package segment"),
                new Case("com.object.thing", "a Kotlin soft-looking hard keyword"),
                new Case("Com.Example", "uppercase, which is not a legal lowercase package"),
                new Case("com..example", "an empty segment"),
                new Case(".com.example", "a leading dot"),
                new Case("com.example.", "a trailing dot"),
                new Case("com.example-thing", "a hyphen, which is not a legal identifier character"),
                new Case("com.1example", "a segment starting with a digit"),
                new Case("com.example; rm -rf /", "shell metacharacters"),
                new Case("com.example$(whoami)", "command substitution"),
                new Case("com.example`id`", "backtick substitution"),
                new Case("com.example\nmalicious", "an embedded newline, which splits a file's syntax"),
                new Case("com.example\u0000", "a NUL, which truncates a path in C"),
                new Case("com.example/../etc", "traversal smuggled into an identifier"),
                new Case("com.example<script>", "markup, for whatever renders this back"),
                new Case("ünïcödé.example", "non-ASCII, which is legal Java and unportable everywhere else"),
                new Case("com." + "x".repeat(300), "absurdly long"));
    }

    /** Values that must never be accepted as a project name. */
    public static List<Case> projectNames() {
        return List.of(
                new Case("", "empty"),
                new Case("My Project", "spaces and uppercase"),
                new Case("-leading-hyphen", "a leading hyphen"),
                new Case("trailing-hyphen-", "a trailing hyphen"),
                new Case("1st-service", "a leading digit"),
                new Case("../escape", "traversal"),
                new Case("con", "a Windows device name"),
                new Case("a".repeat(100), "longer than the 64-character cap"),
                new Case("project;rm -rf /", "shell metacharacters"),
                new Case("project\u0000", "a NUL"));
    }

    /** Paths a rendered template must never be allowed to produce. */
    public static List<Case> paths() {
        return List.of(
                new Case("", "empty"),
                new Case("../outside.txt", "traversal"),
                new Case("src/../../outside.txt", "traversal in the middle"),
                new Case("/etc/passwd", "absolute"),
                new Case("C:/Windows/system32/x.dll", "a drive letter"),
                new Case("src\\main\\Thing.java", "backslash separators"),
                new Case("src//Thing.java", "an empty segment"),
                new Case("src/./Thing.java", "a single-dot segment"),
                new Case("src/", "a directory rather than a file"),
                new Case("CON", "a Windows device name"),
                new Case("src/aux.java", "a Windows device name with an extension"),
                new Case("src/NUL.txt", "a Windows device name, upper case"),
                new Case("src/Thing.java ", "a trailing space, which Windows strips"),
                new Case("src/Thing.", "a trailing dot, which Windows strips"),
                new Case("src/Thing<1>.java", "characters Windows forbids"),
                new Case("src/Thing\u0000.java", "a NUL"),
                new Case("src/Thing\n.java", "a newline in a file name"),
                new Case("src/" + "x".repeat(300) + ".java", "a segment longer than any filesystem allows"));
    }

    /**
     * Values that are <b>accepted</b>, and are here to keep the rules from being tightened into
     * uselessness.
     *
     * <p>A validator with no positive cases drifts towards rejecting everything, because every
     * rejection makes a test greener. These are the shapes real projects use.
     */
    public static List<String> acceptableIdentifiers() {
        return List.of("com.example", "com.example.orders", "com.acme.order_service", "dev.kitbash.core", "a.b.c");
    }

    /** Project names that must keep working. */
    public static List<String> acceptableProjectNames() {
        return List.of("demo", "order-service", "storefront", "a", "x1-y2-z3");
    }

    /** Paths that must keep working, including the ones that look alarming and are not. */
    public static List<String> acceptablePaths() {
        return List.of(
                "README.md",
                "src/main/java/com/example/demo/Thing.java",
                ".gitignore",
                ".github/workflows/ci.yml",
                "src/main/resources/db/migration/V1__init.sql",
                "gradle/wrapper/gradle-wrapper.properties",
                // A dot-prefixed segment is not a traversal, and a file called "context.txt"
                // starts with "con" without being the device "con".
                ".config/settings.json",
                "src/context.txt");
    }
}
