package dev.kitbash.api.push;

import dev.kitbash.api.store.Generation;
import dev.kitbash.api.store.GenerationRepository;
import dev.kitbash.core.pipeline.GeneratedProject;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * A pushed generation, in history (§10, §46).
 *
 * <p>§46 asks that the pushed project URL be recorded <i>alongside the usual lock and digest</i>,
 * and the reason is what somebody comes back to history for: not to re-download a zip, but to find
 * where the project went.
 *
 * <p>Behind an {@link ObjectProvider} because history is a row and rows need a database. A
 * deployment without one can still push — the generation happens, the project is created, the code
 * lands — it simply cannot remember afterwards. Refusing the push in that case would be withholding
 * the feature to protect the receipt.
 */
@Component
public class PushRecorder {

    private static final Logger log = LoggerFactory.getLogger(PushRecorder.class);

    private final ObjectProvider<GenerationRepository> generations;

    public PushRecorder(ObjectProvider<GenerationRepository> generations) {
        this.generations = generations;
    }

    /**
     * Writes the row and marks where it went.
     *
     * <p>A failure here is logged and swallowed. The push has already happened: the user's code is
     * in their group, and turning a bookkeeping failure into an error response would tell them it
     * did not work when it did — which is the opposite of what §46 asks for in the partial case.
     */
    public void pushed(GeneratedProject project, UUID owner, String projectUrl, String commitId) {
        GenerationRepository repository = generations.getIfAvailable();
        if (repository == null) {
            return;
        }
        try {
            Generation row = repository.insert(new Generation(
                    UUID.randomUUID(),
                    owner,
                    null,
                    project.projectName(),
                    project.workspace().files().isEmpty() ? "{}" : selectionOf(project),
                    lockOf(project),
                    project.lock().catalogDigest(),
                    project.selectionHash(),
                    // No artifact key: nothing was cached, because nothing was downloaded. The
                    // project is the artifact, and it is in GitLab.
                    null,
                    dev.kitbash.api.store.GenerationStatus.SUCCEEDED,
                    null,
                    Math.toIntExact(project.totalBytes()),
                    java.time.Instant.now(),
                    java.time.Instant.now().plus(java.time.Duration.ofDays(30)),
                    false,
                    projectUrl));
            log.info("Recorded push generation={} commit={}", row.id(), commitId);
        } catch (RuntimeException unrecorded) {
            // §10 keeps names out of logs; the message here carries none.
            log.warn("A push succeeded but was not recorded in history", unrecorded);
        }
    }

    private static String selectionOf(GeneratedProject project) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(project.resolution().effectiveOptions());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private static String lockOf(GeneratedProject project) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(project.lock());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }
}
