package dev.kitbash.core.patch;

import java.util.Optional;

/**
 * A block-aware editor for {@code build.gradle.kts}.
 *
 * <p>§10 asks for "a structured block-aware editor rather than a regex", and the reason is visible
 * the first time a regex meets a real build script: {@code dependencies} appears inside string
 * literals, inside comments, and inside nested blocks such as {@code subprojects { dependencies {
 * … } }}. A pattern match finds the wrong one, and the failure is a generated project that does
 * not compile.
 *
 * <p>So the source is scanned once, tracking string literals, raw strings, line comments and block
 * comments, and brace depth is counted properly. Parsing Kotlin is not the goal and would be
 * absurd here; knowing where a top-level block begins and ends is enough, and it is the part that
 * has to be right.
 */
final class GradleBuildFile {

    private GradleBuildFile() {}

    /**
     * Adds {@code configuration(notation)} to the top-level {@code dependencies} block, creating
     * the block when it is missing and doing nothing when the exact line is already there.
     */
    static String addDependency(String source, String configuration, String notation) {
        String entry = configuration + "(" + notation + ")";
        Optional<Block> block = findTopLevelBlock(source, "dependencies");
        if (block.isEmpty()) {
            String separator = source.isEmpty() || source.endsWith("\n") ? "" : "\n";
            return source + separator + "\ndependencies {\n    " + entry + "\n}\n";
        }

        Block dependencies = block.get();
        String body = source.substring(dependencies.bodyStart(), dependencies.bodyEnd());
        if (containsEntry(body, entry)) {
            return source;
        }

        String indent = indentOf(body);
        String insertion = indent + entry + "\n";
        String prefix = source.substring(0, dependencies.bodyEnd());
        if (!prefix.endsWith("\n")) {
            insertion = "\n" + insertion;
        }
        return prefix + insertion + source.substring(dependencies.bodyEnd());
    }

    /** Whether the block already declares this exact entry, ignoring leading whitespace. */
    private static boolean containsEntry(String body, String entry) {
        return body.lines().map(String::strip).anyMatch(line -> line.equals(entry));
    }

    /** The indentation existing entries use, so an inserted line does not stand out in a diff. */
    private static String indentOf(String body) {
        return body.lines()
                .filter(line -> !line.isBlank())
                .map(line ->
                        line.substring(0, line.length() - line.stripLeading().length()))
                .findFirst()
                .orElse("    ");
    }

    /** Where a top-level {@code name { … }} block's body starts and ends. */
    private record Block(int bodyStart, int bodyEnd) {}

    private static Optional<Block> findTopLevelBlock(String source, String name) {
        Scanner scanner = new Scanner(source);
        while (scanner.advance()) {
            if (scanner.depth() != 0 || !scanner.atWord(name)) {
                continue;
            }
            int afterName = scanner.position() + name.length();
            int brace = nextNonWhitespace(source, afterName);
            if (brace < 0 || source.charAt(brace) != '{') {
                continue;
            }
            int bodyEnd = scanner.skipToMatchingBrace(brace);
            if (bodyEnd < 0) {
                return Optional.empty();
            }
            return Optional.of(new Block(brace + 1, bodyEnd));
        }
        return Optional.empty();
    }

    private static int nextNonWhitespace(String source, int from) {
        for (int i = from; i < source.length(); i++) {
            if (!Character.isWhitespace(source.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Walks the source once, skipping over everything where a brace or an identifier does not mean
     * what it looks like: {@code "…"}, {@code """…"""}, {@code // …} and {@code /* … *}{@code /}.
     */
    private static final class Scanner {

        private final String source;
        private int index = -1;
        private int depth;

        Scanner(String source) {
            this.source = source;
        }

        int position() {
            return index;
        }

        int depth() {
            return depth;
        }

        boolean advance() {
            index = skipNoise(index + 1);
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (Character.isJavaIdentifierStart(c)) {
                    return true;
                }
                index = skipNoise(index + 1);
            }
            return false;
        }

        /** True when an identifier starts exactly here and is not part of a longer one. */
        boolean atWord(String word) {
            if (!source.startsWith(word, index)) {
                return false;
            }
            int before = index - 1;
            int after = index + word.length();
            boolean boundedLeft = before < 0 || !Character.isJavaIdentifierPart(source.charAt(before));
            boolean boundedRight = after >= source.length() || !Character.isJavaIdentifierPart(source.charAt(after));
            return boundedLeft && boundedRight;
        }

        /** The index of the {@code }} closing the {@code {} at {@code open}, or -1. */
        int skipToMatchingBrace(int open) {
            int level = 0;
            int i = open;
            while (i < source.length()) {
                i = skipNoise(i);
                if (i >= source.length()) {
                    return -1;
                }
                char c = source.charAt(i);
                if (c == '{') {
                    level++;
                } else if (c == '}') {
                    level--;
                    if (level == 0) {
                        return i;
                    }
                }
                i++;
            }
            return -1;
        }

        /** Advances past comments and string literals, returning the next index that means something. */
        private int skipNoise(int from) {
            int i = from;
            while (i < source.length()) {
                if (source.startsWith("//", i)) {
                    int end = source.indexOf('\n', i);
                    i = end < 0 ? source.length() : end + 1;
                } else if (source.startsWith("/*", i)) {
                    int end = source.indexOf("*/", i + 2);
                    i = end < 0 ? source.length() : end + 2;
                } else if (source.startsWith("\"\"\"", i)) {
                    int end = source.indexOf("\"\"\"", i + 3);
                    i = end < 0 ? source.length() : end + 3;
                } else if (source.charAt(i) == '"') {
                    i = endOfStringLiteral(i);
                } else {
                    return i;
                }
            }
            return i;
        }

        private int endOfStringLiteral(int start) {
            for (int i = start + 1; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    return i + 1;
                } else if (c == '\n') {
                    // An unterminated literal is a broken script; stop rather than run to the end.
                    return i;
                }
            }
            return source.length();
        }
    }
}
