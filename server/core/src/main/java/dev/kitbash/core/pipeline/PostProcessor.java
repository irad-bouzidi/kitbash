package dev.kitbash.core.pipeline;

import dev.kitbash.core.git.GitSkeletonWriter;
import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Stage 6 of §6: everything between "the files are right" and "this is a project somebody can open".
 *
 * <p>Line endings are normalised to LF for every text file. A recipe edited on Windows, or a
 * reference project checked out with {@code core.autocrlf=true}, would otherwise put CRLF into the
 * zip — and the zip has to be byte-identical for identical input (§4), which a checkout setting
 * must not be able to change.
 *
 * <p>Then the git skeleton, so unzipping gives a repository rather than a pile of files (§15). Its
 * author, committer, timestamp and message are all fixed, which is what makes two people generating
 * the same project get the same commit hash — intended, and the only way a commit can appear in a
 * byte-identical zip at all.
 *
 * <p>Entry sort and file modes are not done here: the workspace is sorted by construction and modes
 * travel on each file from the plan, so there is nothing left to fix up.
 */
public final class PostProcessor {

    public static final String COMMIT_MESSAGE = "Initial commit";

    private PostProcessor() {}

    /** Returns the commit id, which callers log and the determinism tests assert on. */
    public static String postProcess(Workspace workspace) {
        normaliseLineEndings(workspace);
        return GitSkeletonWriter.write(workspace, COMMIT_MESSAGE);
    }

    /**
     * Files that are broken by LF. {@code cmd.exe} reads a {@code .bat} line by line and a lone LF
     * leaves stray characters in the last token on each line, so a normalised {@code gradlew.bat}
     * fails on the first Windows machine that runs it — which is not a machine CI has.
     */
    private static final java.util.Set<String> KEEPS_CRLF = java.util.Set.of(".bat", ".cmd");

    private static void normaliseLineEndings(Workspace workspace) {
        Map<String, GeneratedFile> files = Map.copyOf(workspace.files());
        files.forEach((path, file) -> {
            byte[] content = file.content();
            if (isBinary(content) || keepsCrlf(path)) {
                return;
            }
            String text = new String(content, StandardCharsets.UTF_8);
            String normalised = text.replace("\r\n", "\n").replace("\r", "\n");
            if (!normalised.equals(text)) {
                workspace.put(path, new GeneratedFile(normalised.getBytes(StandardCharsets.UTF_8), file.executable()));
            }
        });
    }

    private static boolean keepsCrlf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && KEEPS_CRLF.contains(path.substring(dot).toLowerCase(java.util.Locale.ROOT));
    }

    /** Detected by content rather than extension, so a new binary needs no list edited. */
    private static boolean isBinary(byte[] content) {
        int inspected = Math.min(content.length, 8_000);
        for (int i = 0; i < inspected; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
