package dev.kitbash.api.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.api.store.Generation;
import dev.kitbash.api.store.GenerationRepository;
import dev.kitbash.api.store.GenerationStatus;
import dev.kitbash.core.lock.Lock;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeVersion;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Generations, written down (§10, §24).
 *
 * <p>The column that matters is the <b>lock</b>. §7 calls it the valuable part and §10 leans on
 * that: it is what makes a row replayable long after its zip has expired, it is what makes "this
 * used to work" debuggable — diff two locks and the answer is usually right there — and it is
 * small enough that keeping it forever costs nothing.
 *
 * <p>Thirty days, uniformly (§10). A row expires with its artifact unless somebody keeps it, and
 * that one number across all three stores is what makes the story explainable.
 */
@Component
@Primary
@Profile("persistence")
public class StoredGenerations implements GenerationRecorder {

    /** §10: one number across rows, logs and cached zips. */
    static final Duration RETENTION = Duration.ofDays(30);

    private final GenerationRepository generations;
    private final ObjectMapper json;

    public StoredGenerations(GenerationRepository generations, ObjectMapper json) {
        this.generations = generations;
        this.json = json;
    }

    @Override
    public void succeeded(
            Selection selection,
            Lock lock,
            UUID owner,
            String projectName,
            String artifactKey,
            long bytes,
            Duration took) {
        Instant now = Instant.now();
        generations.insert(new Generation(
                UUID.randomUUID(),
                owner,
                null,
                projectName,
                serialize(selection),
                serialize(lock),
                lock.catalogDigest(),
                selection.hash(),
                // Null when nothing cached it: a download then re-renders, which is exactly as
                // correct and merely slower, because the render is deterministic (§4).
                artifactKey,
                GenerationStatus.SUCCEEDED,
                (int) took.toMillis(),
                (int) bytes,
                now,
                now.plus(RETENTION),
                false));
    }

    /**
     * A generation somebody received without anything being rendered for it.
     *
     * <p>The lock is copied from the row that first produced this artifact rather than resolved
     * again: these bytes came from those versions, and recording today's would make the receipt
     * describe a generation that never happened.
     */
    @Override
    public void servedFromCache(Selection selection, UUID owner, String artifactKey, long bytes, Duration took) {
        Instant now = Instant.now();
        Optional<Generation> original = generations.findBySelectionHash(selection.hash()).stream()
                .filter(row -> row.status() == GenerationStatus.SUCCEEDED)
                .findFirst();

        generations.insert(new Generation(
                UUID.randomUUID(),
                owner,
                null,
                selection.projectName(),
                serialize(selection),
                original.map(Generation::lock).orElse("{}"),
                original.map(Generation::catalogDigest).orElse(""),
                selection.hash(),
                artifactKey,
                GenerationStatus.SUCCEEDED,
                (int) took.toMillis(),
                (int) bytes,
                now,
                now.plus(RETENTION),
                false));
    }

    @Override
    public void failed(SelectionEnvelope envelope, String selectionHash, UUID owner, String errorCode, Duration took) {
        Instant now = Instant.now();
        generations.insert(new Generation(
                UUID.randomUUID(),
                owner,
                null,
                envelope.projectName() == null ? "" : envelope.projectName(),
                serialize(envelope),
                // A failed generation never resolved, so it has no lock. The empty object keeps
                // the column non-null without pretending there were versions.
                "{}",
                // No catalog digest either: a generation that never resolved never chose one.
                "",
                // Empty rather than invented when the parse itself failed — the hash is
                // canonical, and a made-up one would collide with real selections in the index
                // §10 put on this column.
                selectionHash == null ? "" : selectionHash,
                null,
                GenerationStatus.FAILED,
                (int) took.toMillis(),
                null,
                now,
                now.plus(RETENTION),
                false));
    }

    /**
     * The §7 envelope, as the row stores it and a replay reads it back.
     *
     * <p>The envelope rather than the parsed selection, because that is the shape the pipeline
     * takes: a replay feeds the row straight back into {@code /generate}'s own entry point rather
     * than reconstructing a parsed object, so there is one way in and nothing to keep in step.
     */
    private String serialize(Selection selection) {
        return serialize(selection.toEnvelope());
    }

    private String serialize(SelectionEnvelope envelope) {
        try {
            return json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a selection for the history", e);
        }
    }

    /**
     * The lock as recipe id to exact version.
     *
     * <p>Ordered, and sorted by id: two locks are compared by a person reading a diff, and a map
     * whose order changed between runs would show every line as changed.
     */
    private String serialize(Lock lock) {
        Map<String, String> versions = new java.util.TreeMap<>();
        lock.recipeVersions()
                .forEach((RecipeId id, RecipeVersion version) -> versions.put(id.value(), version.toString()));
        try {
            return json.writeValueAsString(versions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a lock for the history", e);
        }
    }
}
