package dev.kitbash.api.store;

import static dev.kitbash.api.store.PresetRepository.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Generation history, as rows (§10).
 *
 * <p>Two queries matter and both have an index behind them: one user's history newest first, and
 * everything that produced a given selection hash. The second is not user-facing — §10 uses
 * popular hashes to decide which stacks the verification matrix should prioritise.
 */
public class GenerationRepository {

    private final JdbcClient jdbc;

    public GenerationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Generation insert(Generation generation) {
        jdbc.sql(
                        """
                        insert into generation (id, owner_id, preset_id, project_name, selection, lock,
                                                catalog_digest, selection_hash, artifact_key, status,
                                                duration_ms, size_bytes, created_at, expires_at, kept)
                        values (:id, :ownerId, :presetId, :projectName, cast(:selection as jsonb),
                                cast(:lock as jsonb), :catalogDigest, :selectionHash, :artifactKey, :status,
                                :durationMillis, :sizeBytes, :createdAt, :expiresAt, :kept)
                        """)
                .param("id", generation.id())
                .param("ownerId", generation.ownerId())
                .param("presetId", generation.presetId())
                .param("projectName", generation.projectName())
                .param("selection", generation.selection())
                .param("lock", generation.lock())
                .param("catalogDigest", generation.catalogDigest())
                .param("selectionHash", generation.selectionHash())
                .param("artifactKey", generation.artifactKey())
                .param("status", generation.status().wireName())
                .param("durationMillis", generation.durationMillis())
                .param("sizeBytes", generation.sizeBytes())
                .param("createdAt", java.sql.Timestamp.from(generation.createdAt()))
                .param(
                        "expiresAt",
                        generation.expiresAt() == null ? null : java.sql.Timestamp.from(generation.expiresAt()))
                .param("kept", generation.kept())
                .update();
        return generation;
    }

    public Optional<Generation> findById(UUID id) {
        return jdbc.sql("select * from generation where id = :id")
                .param("id", id)
                .query(GenerationRepository::map)
                .optional();
    }

    /** One user's history, newest first — the query the composite index exists for. */
    public List<Generation> historyFor(UUID ownerId, int limit) {
        return jdbc.sql("select * from generation where owner_id = :ownerId order by created_at desc limit :limit")
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query(GenerationRepository::map)
                .list();
    }

    public List<Generation> findBySelectionHash(String selectionHash) {
        return jdbc.sql("select * from generation where selection_hash = :hash order by created_at desc")
                .param("hash", selectionHash)
                .query(GenerationRepository::map)
                .list();
    }

    /**
     * Keeping a row exempts it from the sweep, which §10 expresses as clearing {@code expires_at}
     * rather than as a second policy. One number across all three stores — "anything older than a
     * month is gone unless you kept it" — is what makes the story explainable.
     */
    public int keep(UUID id) {
        return jdbc.sql("update generation set kept = true, expires_at = null where id = :id")
                .param("id", id)
                .update();
    }

    /**
     * Forgetting the artifact without forgetting the row.
     *
     * <p>This is what the sweep does to a kept row's zip after a year: the row still replays,
     * because the lock is the valuable part — it just re-renders.
     */
    public int releaseArtifact(UUID id) {
        return jdbc.sql("update generation set artifact_key = null where id = :id")
                .param("id", id)
                .update();
    }

    static Generation map(ResultSet row, int rowNumber) throws SQLException {
        return new Generation(
                row.getObject("id", UUID.class),
                row.getObject("owner_id", UUID.class),
                row.getObject("preset_id", UUID.class),
                row.getString("project_name"),
                row.getString("selection"),
                row.getString("lock"),
                row.getString("catalog_digest"),
                row.getString("selection_hash"),
                row.getString("artifact_key"),
                GenerationStatus.of(row.getString("status")),
                (Integer) row.getObject("duration_ms"),
                (Integer) row.getObject("size_bytes"),
                instant(row, "created_at"),
                instant(row, "expires_at"),
                row.getBoolean("kept"));
    }
}
