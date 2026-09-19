package dev.kitbash.api.generate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * PHASE-0 SCAFFOLDING — deleted by kitbash-13.
 *
 * <p>The reference project as it sits on the classpath, staged there by the {@code
 * stageReferenceProject} Gradle task.
 *
 * <p>Each file is a numbered blob and the manifest holds its real path and mode. Two reasons: a jar
 * has no file modes, so {@code gradlew} would arrive non-executable; and {@code processResources}
 * inherits Ant's default excludes, which silently drop {@code .gitignore} and {@code .git/}.
 */
final class ReferenceProject {

    private static final String MANIFEST = "reference/project.manifest";
    private static final String ROOT = "reference/project/";

    record Entry(String path, boolean executable, byte[] content) {}

    private ReferenceProject() {}

    static List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        for (String line : read(MANIFEST).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            // blob<TAB>mode<TAB>path
            String[] parts = line.split("\t", 3);
            entries.add(new Entry(parts[2], parts[1].equals("755"), readBytes(ROOT + parts[0])));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("the reference project is missing from the classpath");
        }
        return entries;
    }

    private static String read(String resource) {
        return new String(readBytes(resource), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(String resource) {
        try (InputStream in = ReferenceProject.class.getClassLoader().getResourceAsStream(resource)) {
            return Objects.requireNonNull(in, () -> "missing classpath resource: " + resource)
                    .readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }
}
