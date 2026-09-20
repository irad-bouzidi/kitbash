package dev.kitbash.core.plan;

/**
 * The only two modes a generated file is ever given.
 *
 * <p>A generator that can emit arbitrary Unix modes is a generator that can emit setuid binaries;
 * two constants is all the expressiveness the output needs. The one file that genuinely has to be
 * executable is {@code gradlew}, and a project whose wrapper arrives at 0644 is broken on the first
 * command its README tells you to run.
 */
public enum FileMode {
    REGULAR(0_100644),
    EXECUTABLE(0_100755);

    private final int unixMode;

    FileMode(int unixMode) {
        this.unixMode = unixMode;
    }

    public int unixMode() {
        return unixMode;
    }

    public boolean executable() {
        return this == EXECUTABLE;
    }

    public static FileMode of(boolean executable) {
        return executable ? EXECUTABLE : REGULAR;
    }
}
