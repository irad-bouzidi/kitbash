package dev.kitbash.core.workspace;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
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

    public void put(String path, GeneratedFile file) {
        files.put(validatePath(path), Objects.requireNonNull(file, "file"));
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

    /**
     * Rejects the paths that would let a generated project write outside its own directory. The
     * full traversal defence and the size caps land in kitbash-20; this is the floor below which
     * nothing should be written at all.
     */
    private static String validatePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (path.startsWith("/") || path.contains("\\")) {
            throw new IllegalArgumentException("path must be relative and /-separated: " + path);
        }
        if (path.contains("//")) {
            throw new IllegalArgumentException("path must not contain an empty segment: " + path);
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path must not traverse: " + path);
            }
        }
        return path;
    }
}
