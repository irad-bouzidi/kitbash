package dev.kitbash.api.store;

import static dev.kitbash.api.store.PresetRepository.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verification runs, as rows (§10).
 *
 * <p>This is the one repository with a real concurrency requirement, and it is why §10 puts a
 * partial unique index on {@code (selection_hash, catalog_digest)}. Two people asking to verify
 * the same selection against the same catalog must produce one run, not two — and "check whether
 * one exists, then insert" is a race with a window wide enough to lose under ordinary load.
 *
 * <p>So the claim is a single statement. The database decides the winner, and the loser reads back
 * the row that already exists instead of creating a second container's worth of work.
 */
public class VerificationRunRepository {

    private final JdbcClient jdbc;

    public VerificationRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The run for this selection and catalog: the caller's, or the one already in flight.
     *
     * <p>{@code on conflict do nothing} rather than an exception, because losing this race is the
     * normal case rather than an error — it is the dedupe working. The returned row says which
     * happened: an id equal to the requested one means the caller's insert won.
     */
    public VerificationRun claim(VerificationRun requested) {
        int inserted = jdbc.sql(
                        """
                        insert into verification_run (id, requested_by, selection, lock, selection_hash,
                                                      catalog_digest, status, log_key, started_at,
                                                      finished_at, expires_at)
                        values (:id, :requestedBy, cast(:selection as jsonb), cast(:lock as jsonb),
                                :selectionHash, :catalogDigest, :status, :logKey, :startedAt,
                                :finishedAt, :expiresAt)
                        on conflict do nothing
                        """)
                .param("id", requested.id())
                .param("requestedBy", requested.requestedBy())
                .param("selection", requested.selection())
                .param("lock", requested.lock())
                .param("selectionHash", requested.selectionHash())
                .param("catalogDigest", requested.catalogDigest())
                .param("status", requested.status().wireName())
                .param("logKey", requested.logKey())
                .param("startedAt", timestamp(requested.startedAt()))
                .param("finishedAt", timestamp(requested.finishedAt()))
                .param("expiresAt", timestamp(requested.expiresAt()))
                .update();

        if (inserted == 1) {
            return requested;
        }
        // Somebody else got there first. Their run is the answer to this request.
        return active(requested.selectionHash(), requested.catalogDigest())
                .orElseThrow(() -> new IllegalStateException(
                        "The insert was refused but no active run exists for " + requested.selectionHash()
                                + " — the dedupe index and this query disagree, which should be impossible."));
    }

    /** The run the dedupe index considers blocking: pending, running or already passed. */
    public Optional<VerificationRun> active(String selectionHash, String catalogDigest) {
        return jdbc.sql(
                        """
                        select * from verification_run
                        where selection_hash = :hash and catalog_digest = :digest
                          and status in ('pending', 'running', 'passed')
                        """)
                .param("hash", selectionHash)
                .param("digest", catalogDigest)
                .query(VerificationRunRepository::map)
                .optional();
    }

    public Optional<VerificationRun> findById(UUID id) {
        return jdbc.sql("select * from verification_run where id = :id")
                .param("id", id)
                .query(VerificationRunRepository::map)
                .optional();
    }

    public int markRunning(UUID id, Instant startedAt) {
        return jdbc.sql("update verification_run set status = 'running', started_at = :startedAt where id = :id")
                .param("id", id)
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    /**
     * The end of a run.
     *
     * <p>Finishing as {@code failed} is what releases the dedupe: the row leaves the partial
     * index, and the next request for the same selection starts a fresh run. That is deliberate —
     * a failure is a result somebody may want to reproduce once the recipe is fixed.
     */
    public int finish(UUID id, VerificationStatus status, String logKey, Instant finishedAt, Instant expiresAt) {
        return jdbc.sql(
                        """
                        update verification_run
                        set status = :status, log_key = :logKey, finished_at = :finishedAt, expires_at = :expiresAt
                        where id = :id
                        """)
                .param("id", id)
                .param("status", status.wireName())
                .param("logKey", logKey)
                .param("finishedAt", timestamp(finishedAt))
                .param("expiresAt", timestamp(expiresAt))
                .update();
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    static VerificationRun map(ResultSet row, int rowNumber) throws SQLException {
        return new VerificationRun(
                row.getObject("id", UUID.class),
                row.getObject("requested_by", UUID.class),
                row.getString("selection"),
                row.getString("lock"),
                row.getString("selection_hash"),
                row.getString("catalog_digest"),
                VerificationStatus.of(row.getString("status")),
                row.getString("log_key"),
                instant(row, "started_at"),
                instant(row, "finished_at"),
                instant(row, "expires_at"));
    }
}
