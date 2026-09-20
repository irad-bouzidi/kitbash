package dev.kitbash.api.history;

import dev.kitbash.core.lock.Lock;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionEnvelope;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Where a generation is written down (§10).
 *
 * <p>An interface with a no-op default, for the same reason {@code ZipCache} is one: the generator
 * half of this service runs without a database (§10, §12), and {@code /generate} must not start
 * needing one. A deployment with rows records them; a laptop without gets the zip and no receipt.
 *
 * <p>Recording happens <b>after</b> the response has been streamed. §24 is explicit that it must
 * not slow the stream, and the two numbers worth having — how long it took and how big it was —
 * are only known once it is over.
 */
public interface GenerationRecorder {

    /**
     * A generation that produced a zip.
     *
     * @param artifactKey the object store key the bytes are under, or null when nothing kept them
     */
    void succeeded(
            Selection selection,
            Lock lock,
            UUID owner,
            String projectName,
            String artifactKey,
            long bytes,
            Duration took);

    /**
     * A generation served from the cache.
     *
     * <p>Still a row: §24 records what somebody downloaded, and a download that happened to be
     * cheap is still a download. The lock comes from the row the artifact was first written for,
     * because that is what produced these exact bytes — re-resolving now could record versions
     * that had nothing to do with them.
     */
    void servedFromCache(Selection selection, UUID owner, String artifactKey, long bytes, Duration took);

    /**
     * A generation that did not.
     *
     * <p>§24 asks for this specifically: a history that only contains successes cannot answer
     * "why did this break", which is the question people actually bring to a history page.
     *
     * <p>It takes the <b>envelope</b> rather than a parsed selection, because the commonest
     * failure is the parse itself — a mistyped recipe id never becomes a {@code Selection}, and a
     * recorder that demanded one could only record the failures that happened late.
     *
     * @param selectionHash the canonical hash where parsing got far enough to produce one
     */
    void failed(SelectionEnvelope envelope, String selectionHash, UUID owner, String errorCode, Duration took);

    /** The default, for a service with nowhere to write. */
    @Component
    class None implements GenerationRecorder {

        @Override
        public void succeeded(
                Selection selection,
                Lock lock,
                UUID owner,
                String projectName,
                String artifactKey,
                long bytes,
                Duration took) {
            // Nowhere to write it.
        }

        @Override
        public void servedFromCache(Selection selection, UUID owner, String artifactKey, long bytes, Duration took) {
            // As above.
        }

        @Override
        public void failed(
                SelectionEnvelope envelope, String selectionHash, UUID owner, String errorCode, Duration took) {
            // As above.
        }
    }
}
