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
                row.getBoolean("kept"),
                row.getString("pushed_project_url"));
    }

    /**
     * The stacks people actually build, most-generated first (§10, kitbash-40).
     *
     * <p>{@code generation_selection_hash_idx} exists for this query and says so in the migration.
     * §12 calls history-fed verification <i>the piece neither plan had</i>: an enumerated matrix
     * tests the catalog's cross-product, which is not the same set as the stacks a team relies on,
     * and a combination that is unusual on paper but is one team's house standard deserves nightly
     * coverage more than a cell nobody has ever generated.
     *
     * <p>Only successful generations count. A row that failed is a selection somebody <em>tried</em>,
     * and the matrix already has an opinion about those — putting them here would fill the nightly
     * with combinations already known not to work.
     *
     * <p>{@code project_name} is not selected. §10 keeps it out of anything that leaves this table,
     * and the way to keep a column out of a consumer is not to read it.
     */
    public List<PopularSelection> mostGenerated(int limit) {
        return jdbc.sql(
                        """
                        select selection_hash, count(*) as generations, min(selection::text) as selection
                        from generation
                        where status = 'succeeded'
                        group by selection_hash
                        order by generations desc, selection_hash
                        limit :limit
                        """)
                .param("limit", limit)
                .query((row, number) -> new PopularSelection(
                        row.getString("selection_hash"), row.getInt("generations"), row.getString("selection")))
                .list();
    }

    /**
     * One popular selection: how it was made, and how often.
     *
     * @param selection the envelope as stored, <b>names included</b> — stripping them is the
     *     caller's job and is done in one place, where there is a test for it
     */
    public record PopularSelection(String selectionHash, int generations, String selection) {}

    /**
     * Records where a generation was pushed (§46).
     *
     * <p>An update rather than a column on the insert, because the order is what it is: the row
     * exists the moment the project is generated, and a push happens after — or not at all, which
     * is the usual case.
     */
    public int markPushed(UUID id, String projectUrl) {
        return jdbc.sql("update generation set pushed_project_url = :url where id = :id")
                .param("id", id)
                .param("url", projectUrl)
                .update();
    }
}
