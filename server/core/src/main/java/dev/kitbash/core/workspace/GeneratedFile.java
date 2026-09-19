package dev.kitbash.core.workspace;

import java.util.Arrays;
import java.util.Objects;

/**
 * One file in a generated project: its bytes and whether it is executable.
 *
 * <p>The executable bit is carried explicitly rather than inferred from the path, because it has to
 * survive a round trip through a jar (which has no modes) and land correctly in the zip.
 */
public record GeneratedFile(byte[] content, boolean executable) {

    public GeneratedFile {
        Objects.requireNonNull(content, "content");
        content = content.clone();
    }

    public static GeneratedFile of(byte[] content) {
        return new GeneratedFile(content, false);
    }

    public static GeneratedFile executable(byte[] content) {
        return new GeneratedFile(content, true);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    /** The Unix mode this file is stored with: 0755 when executable, 0644 otherwise. */
    public int unixMode() {
        return executable ? 0_100755 : 0_100644;
    }

    public int size() {
        return content.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GeneratedFile file
                && executable == file.executable
                && Arrays.equals(content, file.content);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(content) + Boolean.hashCode(executable);
    }

    @Override
    public String toString() {
        return "GeneratedFile[%d bytes%s]".formatted(content.length, executable ? ", executable" : "");
    }
}
