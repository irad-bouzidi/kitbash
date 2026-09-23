package dev.kitbash.api.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The nightly's logs, where the runner left them.
 *
 * <p>Separate from {@link VerificationLogs}, which holds the logs of runs <em>this</em> API started
 * and keys them by run id in object storage. These are the matrix's, written to files beside
 * {@code verification.json} by a process that has no database and no bucket — so they are read the
 * way they were written, and the two are not forced into one interface for the sake of symmetry.
 */
public class MatrixLogs {

    private static final Logger log = LoggerFactory.getLogger(MatrixLogs.class);

    /**
     * The largest log served in one response.
     *
     * <p>A failing Gradle log runs to megabytes, and the viewer shows the end of it anyway. Sending
     * the tail rather than the file keeps one curious click from being a hundred-megabyte response.
     */
    private static final int TAIL_BYTES = 512 * 1024;

    private final Path directory;

    public MatrixLogs(Path directory) {
        this.directory = directory;
    }

    /** The log for a cell, or empty when the run that produced it has been cleaned away. */
    public Optional<String> forCell(String cellId) {
        Path file = directory.resolve(cellId + ".log");
        // Belt as well as braces: the caller has already checked the id against the published run,
        // and a path that escaped this directory would still be refused here.
        if (!file.normalize().startsWith(directory.normalize()) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] all = Files.readAllBytes(file);
            if (all.length <= TAIL_BYTES) {
                return Optional.of(new String(all, java.nio.charset.StandardCharsets.UTF_8));
            }
            String tail = new String(all, all.length - TAIL_BYTES, TAIL_BYTES, java.nio.charset.StandardCharsets.UTF_8);
            return Optional.of("[earlier output omitted — this is the last %d KB]%n%s"
                    .formatted(TAIL_BYTES / 1024, tail.substring(tail.indexOf('\n') + 1)));
        } catch (IOException unreadable) {
            log.warn("The matrix log for {} could not be read ({})", cellId, unreadable.getMessage());
            return Optional.empty();
        }
    }
}
