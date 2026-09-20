package dev.kitbash.core.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a hook's compiled class for references to the things §4 says a hook may not touch.
 *
 * <p>{@link PlanContext} already withholds every capability a hook would need to misbehave, so the
 * only way left is reaching for a static — {@code Files.readString}, {@code System.nanoTime},
 * {@code new Random()}, {@code Runtime.getRuntime()}. Those names all appear verbatim in the class
 * file's constant pool, so scanning the bytes finds them.
 *
 * <p>This is deliberately crude. A precise check would parse the constant pool properly and would
 * still be defeatable by reflection; what it buys is that the ordinary mistake — somebody adding a
 * timestamp or reading a file because it was convenient — cannot pass review unnoticed. A hook that
 * genuinely needs something from this list is a hook that should not exist.
 */
final class HookPurity {

    /** Class and method names whose presence in a hook means it is doing something it should not. */
    private static final List<String> FORBIDDEN = List.of(
            "java/io/File",
            "java/io/FileInputStream",
            "java/io/FileOutputStream",
            "java/nio/file/Files",
            "java/nio/file/Paths",
            "java/net/",
            "java/lang/ProcessBuilder",
            "java/lang/Runtime",
            "java/util/Random",
            "java/time/Instant",
            "java/time/LocalDate",
            "currentTimeMillis",
            "nanoTime",
            "getenv",
            "getProperty");

    private HookPurity() {}

    /** The forbidden references this class makes, empty when the hook is clean. */
    static List<String> violations(Class<?> hook) {
        String bytecode = new String(read(hook), StandardCharsets.ISO_8859_1);
        List<String> found = new ArrayList<>();
        FORBIDDEN.stream().filter(bytecode::contains).forEach(found::add);
        return found;
    }

    private static byte[] read(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Could not read the class file for " + type.getName());
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
