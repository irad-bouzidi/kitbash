package dev.kitbash.api.preview;

import java.util.List;

/**
 * The file tree, and nothing else (§8, §9).
 *
 * <p>Paths and sizes only. The temptation is to inline the small files — most of a generated
 * project is small — but that makes the payload's size depend on what was selected rather than on
 * how many files there are, and a tree that is sometimes 12 KB and sometimes 400 KB is one nobody
 * can reason about. A file arrives when it is clicked (§9).
 *
 * @param fileCount the whole tree's size, so a client can say "48 files" without counting
 * @param totalBytes what the project weighs before it is zipped
 */
public record PreviewTree(
        String projectName,
        String catalogDigest,
        String selectionHash,
        int fileCount,
        long totalBytes,
        List<Entry> files) {

    /**
     * One file.
     *
     * @param binary whether asking for its content will return bytes rather than text — decided
     *     here so the viewer never has to guess from an extension
     */
    public record Entry(String path, long bytes, boolean binary) {}
}
