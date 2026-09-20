package dev.kitbash.core.util;

import java.util.Locale;
import java.util.Set;

/**
 * What Windows will not let a file or directory be called.
 *
 * <p>It lives here, alone, because two layers need it and neither should own it: the identifier
 * allowlist rejects a <i>project name</i> that would become an unusable directory, and the path
 * check rejects a rendered <i>path</i> that would become an unusable file. One copy in a shared
 * place beats two copies that agree today.
 *
 * <p>None of this is hypothetical politeness. A zip is extracted wherever the person who
 * downloaded it works, and a name this generator is happy with can be uncreatable there — a broken
 * project produced by a green pipeline, which is the worst kind.
 */
public final class WindowsNames {

    /**
     * Device names MS-DOS reserved, which Windows still refuses to create — with or without an
     * extension, so {@code CON.java} is as unusable as {@code CON}.
     */
    private static final Set<String> DEVICE_NAMES = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1",
            "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** Characters Windows forbids in a file name; on a POSIX filesystem they are merely awful. */
    public static final String FORBIDDEN_CHARACTERS = "<>:\"|?*";

    private WindowsNames() {}

    /** {@code CON}, {@code con.txt} and {@code CON.tar.gz} are all the device {@code con}. */
    public static boolean isDeviceName(String segment) {
        int dot = segment.indexOf('.');
        return DEVICE_NAMES.contains((dot < 0 ? segment : segment.substring(0, dot)).toLowerCase(Locale.ROOT));
    }
}
