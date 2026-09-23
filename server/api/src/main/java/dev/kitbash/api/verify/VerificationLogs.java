package dev.kitbash.api.verify;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Where a run's log goes, and where {@code GET /verify/{id}} reads it from (§10).
 *
 * <p>A seam rather than a direct S3 call for the reason §10 gives about the zip cache: the log is
 * served <b>through the API</b>, never as a bucket link. A signed URL would be a second way in,
 * with its own expiry and its own answer to "may this person read this", and the question of who
 * may read a run belongs to the code that knows what a run is.
 *
 * <p>The key is the run's id. Nothing is derived from the selection here — two runs of the same
 * selection are the same run, and a run that failed and was retried is genuinely a different one
 * whose log must not be overwritten by its predecessor's.
 */
public interface VerificationLogs {

    /** Stores the log of a finished run, returning the key the row should remember. */
    String put(String runId, String log);

    /** The log for a key a row remembers, if the store still has it. */
    Optional<String> find(String key);

    /**
     * The default: keep the log in memory for as long as the process lives.
     *
     * <p>Honest rather than useful. §10 gives logs a 30-day expiry in object storage, which is
     * {@link ObjectStoreVerificationLogs} under the {@code objectstore} profile; without that
     * profile there is nowhere durable to put them, and pretending otherwise would mean a
     * {@code logKey} in the database pointing at nothing after a restart.
     */
    @Component
    @org.springframework.context.annotation.Profile("persistence")
    class InMemory implements VerificationLogs {

        private final java.util.Map<String, String> logs = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String put(String runId, String log) {
            logs.put(runId, log);
            return runId;
        }

        @Override
        public Optional<String> find(String key) {
            return Optional.ofNullable(logs.get(key));
        }
    }
}
