package dev.kitbash.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The matrix as a page somebody can look at, and as JSON something can read.
 *
 * <p>§12 has the results feeding two audiences: a status page for people, and — from {@code
 * kitbash-38} — the wizard's per-combination badges. Writing both from the same run means the
 * badge and the page can never disagree about a cell.
 *
 * <p>Written under {@code verification/build/}, which is output rather than source. A checked-in
 * status page is a status page somebody forgets to regenerate, and then it lies.
 */
public final class StatusPage {

    private StatusPage() {}

    public static void write(CellResult.Matrix matrix, Repository repository) {
        write(matrix, repository, List.of());
    }

    /**
     * @param historyNotes what §40's history pass did and did not add — a popular stack skipped
     *     because a recipe was removed, or the fact that no history was available at all. Reported
     *     rather than silent: a nightly that quietly stopped running history cells would look
     *     exactly like one where every history cell passed.
     */
    public static void write(CellResult.Matrix matrix, Repository repository, List<String> historyNotes) {
        Path directory = repository.output();
        writeFile(directory.resolve("status.json"), json(matrix));
        writeFile(directory.resolve("status.html"), html(matrix, historyNotes));
    }

    static String json(CellResult.Matrix matrix) {
        List<String> cells = new ArrayList<>();
        for (CellResult result : matrix.results()) {
            cells.add(
                    """
                        {
                          "cell": "%s",
                          "outcome": "%s",
                          "durationSeconds": %d,
                          "failedStep": %s,
                          "reproduce": "%s"
                        }"""
                            .formatted(
                                    result.cellId(),
                                    result.outcome(),
                                    result.duration().toSeconds(),
                                    result.failedStep() == null ? "null" : "\"" + result.failedStep() + "\"",
                                    result.reproduce()));
        }
        return """
                {
                  "trigger": "%s",
                  "catalogDigest": "%s",
                  "green": %s,
                  "durationSeconds": %d,
                  "cells": [
                %s
                  ]
                }
                """
                .formatted(
                        matrix.trigger(),
                        matrix.catalogDigest(),
                        matrix.green(),
                        matrix.duration().toSeconds(),
                        String.join(",\n", cells));
    }

    static String html(CellResult.Matrix matrix) {
        return html(matrix, List.of());
    }

    static String html(CellResult.Matrix matrix, List<String> historyNotes) {
        StringBuilder rows = new StringBuilder();
        for (CellResult result : matrix.results()) {
            // §40 asks that history-sourced cells be labelled as such, with their usage count, so
            // it is obvious which failures affect real users rather than a combination of the
            // cross-product nobody has ever generated. Their ids begin `h-`.
            boolean fromHistory = result.cellId().startsWith("h-");
            rows.append(
                    """
                        <tr class="%s">
                          <td>%s%s</td>
                          <td>%s</td>
                          <td>%s</td>
                          <td><code>%s</code></td>
                        </tr>
                    """
                            .formatted(
                                    result.passed() ? "passed" : "failed",
                                    escape(result.cellId()),
                                    fromHistory ? " <span class=\"history\">from history</span>" : "",
                                    result.outcome(),
                                    humanise(result.duration()),
                                    escape(result.reproduce())));
        }

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>kitbash verification — %s</title>
                  <style>
                    :root { color-scheme: light dark; }
                    body { font-family: system-ui, sans-serif; margin: 2rem; max-width: 60rem; line-height: 1.5; }
                    table { border-collapse: collapse; width: 100%%; }
                    th, td { text-align: left; padding: 0.5rem 0.75rem; border-bottom: 1px solid color-mix(in srgb, currentColor 15%%, transparent); }
                    tr.passed td:nth-child(2) { color: color-mix(in srgb, green 70%%, currentColor); }
                    tr.failed td:nth-child(2) { color: color-mix(in srgb, red 70%%, currentColor); }
                    code { font-size: 0.85em; opacity: 0.8; }
                    .summary { font-size: 1.1rem; }
                    .history { font-size: 0.75em; padding: 0.1rem 0.4rem; border-radius: 0.5rem;
                               background: color-mix(in srgb, currentColor 12%%, transparent); }
                    .notes { font-size: 0.9rem; opacity: 0.85; }
                  </style>
                </head>
                <body>
                  <h1>Verification — %s</h1>
                  <p class="summary">%s · %s · %s</p>
                  <p>Catalog <code>%s</code></p>
                  <table>
                    <thead><tr><th>Cell</th><th>Outcome</th><th>Duration</th><th>Reproduce locally</th></tr></thead>
                    <tbody>
                %s    </tbody>
                  </table>
                %s  <p>Each cell generates a project and runs that stack's real build and test commands in an
                     ecosystem container — never on the API host. A cell that merely compared rendered text
                     against a golden file could still emit projects nobody can compile.</p>
                </body>
                </html>
                """
                .formatted(
                        escape(matrix.trigger()),
                        escape(matrix.trigger()),
                        matrix.green() ? "All green" : matrix.failures() + " failing",
                        plural(matrix.results().size(), "cell"),
                        humanise(matrix.duration()),
                        escape(matrix.catalogDigest()),
                        rows,
                        notes(historyNotes));
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static String humanise(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds < 60 ? seconds + "s" : "%dm %02ds".formatted(seconds / 60, seconds % 60);
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;");
    }

    private static void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }
    }

    /**
     * What the history pass did, when it has anything to say.
     *
     * <p>§40 wants the set of history cells not to drift silently, and the cheapest guard against
     * that is saying out loud when one was dropped and why.
     */
    private static String notes(List<String> historyNotes) {
        if (historyNotes.isEmpty()) {
            return "";
        }
        StringBuilder list = new StringBuilder("  <h2>History cells (§40)</h2>\n  <ul class=\"notes\">\n");
        for (String note : historyNotes) {
            list.append("    <li>").append(escape(note)).append("</li>\n");
        }
        return list.append("  </ul>\n").toString();
    }
}
