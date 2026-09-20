package dev.kitbash.core.pipeline;

import dev.kitbash.core.lock.Lock;
import dev.kitbash.core.pack.DeterministicZipWriter;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.workspace.Workspace;
import java.io.IOException;
import java.io.OutputStream;

/**
 * One finished generation: the files, the receipt, and the ability to stream itself.
 *
 * <p>The {@link Lock} and the selection hash travel with the workspace rather than being recomputed
 * by whoever wants them, because §7 and §10 have three different subsystems recording them — the
 * generation row, the cache key and the verification dedupe key — and three recomputations are
 * three chances to disagree.
 */
public record GeneratedProject(
        Workspace workspace,
        Resolution resolution,
        Lock lock,
        String selectionHash,
        String commitId,
        String projectName) {

    /** Stage 7: fixed timestamps, sorted entries, modes preserved. */
    public void streamTo(OutputStream out) throws IOException {
        DeterministicZipWriter.write(workspace, projectName, out);
    }

    public int fileCount() {
        return workspace.fileCount();
    }

    public long totalBytes() {
        return workspace.totalBytes();
    }
}
