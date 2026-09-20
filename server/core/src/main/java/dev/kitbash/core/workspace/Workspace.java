package dev.kitbash.core.workspace;

import dev.kitbash.core.plan.SafePaths;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The generated project, held entirely in memory.
 *
 * <p>Deliberately not a directory under {@code /tmp/{id}} (plan §2): a generated project is a few
 * hundred kilobytes, so there is no temporary tree, no cleanup job, no disk-full failure mode and
 * no way for one request's files to be visible to another.
 *
 * <p>Paths are relative, {@code /}-separated and never start with a slash. Iteration order is
 * lexicographic by path, which is one of the things the zip writer's determinism rests on.
 */
public final class Workspace {

    private final NavigableMap<String, GeneratedFile> files = new TreeMap<>();

    /** Lowercased path to the one actually written, which is how a case collision becomes visible. */
    private final Map<String, String> byFoldedCase = new HashMap<>();

    public void put(String path, GeneratedFile file) {
        String safe = SafePaths.require(null, path);
        Objects.requireNonNull(file, "file");

        String existing = byFoldedCase.putIfAbsent(safe.toLowerCase(Locale.ROOT), safe);
        if (existing != null && !existing.equals(safe)) {
            throw SafePaths.refuseCaseCollision(null, safe, existing);
        }
        files.put(safe, file);
    }

    public void putText(String path, String content) {
        put(path, GeneratedFile.of(content.getBytes(StandardCharsets.UTF_8)));
    }

    public GeneratedFile get(String path) {
        return files.get(path);
    }

    public boolean contains(String path) {
        return files.containsKey(path);
    }

    /** Every file, ordered by path. */
    public NavigableMap<String, GeneratedFile> files() {
        return Collections.unmodifiableNavigableMap(files);
    }

    public int fileCount() {
        return files.size();
    }

    public long totalBytes() {
        return files.values().stream().mapToLong(GeneratedFile::size).sum();
    }
}
