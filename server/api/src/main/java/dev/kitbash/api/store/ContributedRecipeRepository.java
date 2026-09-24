package dev.kitbash.api.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Submissions and their review, as rows (§47).
 *
 * <p>Three queries and each has an index. The catalog reads only what is visible; the review page
 * reads the queue oldest first, because a submission nobody has looked at is the one that has been
 * waiting longest; and revocation reads one recipe by id and version.
 *
 * <p>Every state change is an update guarded by the state it came from — {@code and status = ...}
 * in the where clause rather than a read, a check and a write. §47 requires approval to follow
 * verification, and two reviewers opening the same submission is the ordinary case rather than the
 * exotic one.
 */
public class ContributedRecipeRepository {

    private final JdbcClient jdbc;

    public ContributedRecipeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ContributedRecipe insert(ContributedRecipe recipe) {
        jdbc.sql(
                        """
                        insert into contributed_recipe (id, recipe_id, namespace, version, manifest, content,
                                                        content_hash, status, submitted_by, submitted_at)
                        values (:id, :recipeId, :namespace, :version, :manifest, cast(:content as jsonb),
                                :contentHash, :status, :submittedBy, :submittedAt)
                        """)
                .param("id", recipe.id())
                .param("recipeId", recipe.recipeId())
                .param("namespace", recipe.namespace())
                .param("version", recipe.version())
                .param("manifest", recipe.manifest())
                .param("content", recipe.content())
                .param("contentHash", recipe.contentHash())
                .param("status", recipe.status().wireName())
                .param("submittedBy", recipe.submittedBy())
                .param("submittedAt", Timestamp.from(recipe.submittedAt()))
                .update();
        return recipe;
    }

    /** What the catalog may show: approved, and not since withdrawn. */
    public List<ContributedRecipe> visible() {
        return jdbc.sql("select * from contributed_recipe where status = 'approved' order by recipe_id, version")
                .query(ContributedRecipeRepository::map)
                .list();
    }

    /** The review queue, oldest first. */
    public List<ContributedRecipe> awaitingReview() {
        return jdbc.sql(
                        """
                        select * from contributed_recipe
                        where status in ('submitted', 'verified')
                        order by submitted_at
                        """)
                .query(ContributedRecipeRepository::map)
                .list();
    }

    public Optional<ContributedRecipe> findById(UUID id) {
        return jdbc.sql("select * from contributed_recipe where id = :id")
                .param("id", id)
                .query(ContributedRecipeRepository::map)
                .optional();
    }

    /**
     * Records that the matrix passed.
     *
     * <p>Guarded on {@code submitted} so a verification result cannot resurrect something already
     * approved or revoked — a matrix run started before a revocation can finish after it.
     */
    public boolean markVerified(UUID id, UUID runId) {
        return jdbc.sql(
                                """
                        update contributed_recipe set status = 'verified', verified_by_run = :runId
                        where id = :id and status = 'submitted'
                        """)
                        .param("id", id)
                        .param("runId", runId)
                        .update()
                == 1;
    }

    /**
     * Approval, by a named human.
     *
     * <p>Guarded on {@code verified} rather than on "not approved", which is the §47 rule made
     * unskippable: a submission that has not built cannot be approved, whatever order the two
     * requests arrive in.
     */
    public boolean approve(UUID id, UUID reviewer, Instant when) {
        return jdbc.sql(
                                """
                        update contributed_recipe
                        set status = 'approved', reviewed_by = :reviewer, reviewed_at = :when
                        where id = :id and status = 'verified'
                        """)
                        .param("id", id)
                        .param("reviewer", reviewer)
                        .param("when", Timestamp.from(when))
                        .update()
                == 1;
    }

    /**
     * Withdrawal.
     *
     * <p>Deliberately not guarded on {@code approved}. A submission that turned out to be
     * malicious should be withdrawable from the queue as well as from the catalog, and a
     * revocation that refused because the thing was never approved would be a revocation that
     * failed exactly when somebody was in a hurry.
     */
    public boolean revoke(UUID id, UUID revoker, String reason, Instant when) {
        return jdbc.sql(
                                """
                        update contributed_recipe
                        set status = 'revoked', revoked_by = :revoker, revoked_reason = :reason, revoked_at = :when
                        where id = :id and status <> 'revoked'
                        """)
                        .param("id", id)
                        .param("revoker", revoker)
                        .param("reason", reason)
                        .param("when", Timestamp.from(when))
                        .update()
                == 1;
    }

    /**
     * Every generation whose lock names this recipe.
     *
     * <p>{@code lock ?? :recipeId} is jsonb key existence — doubled because Spring's named
     * parameter parsing treats a lone {@code ?} as a placeholder. The lock is
     * {@code recipe id -> version}, so key existence is exactly the question "did this generation
     * use that recipe", at any version.
     */
    public List<UUID> generationsUsing(String recipeId) {
        return jdbc.sql("select id from generation where lock ?? :recipeId")
                .param("recipeId", recipeId)
                .query(UUID.class)
                .list();
    }

    /** Flags them, idempotently: revoking twice must not fail, and must not double-report. */
    public int flag(List<UUID> generationIds, String recipeId, String reason, Instant when) {
        int flagged = 0;
        for (UUID generationId : generationIds) {
            flagged += jdbc.sql(
                            """
                            insert into generation_flag (generation_id, recipe_id, reason, flagged_at)
                            values (:generationId, :recipeId, :reason, :when)
                            on conflict (generation_id, recipe_id) do nothing
                            """)
                    .param("generationId", generationId)
                    .param("recipeId", recipeId)
                    .param("reason", reason)
                    .param("when", Timestamp.from(when))
                    .update();
        }
        return flagged;
    }

    /** Every flag on one generation, for the history row that has to render them. */
    public List<GenerationFlag> flagsFor(UUID generationId) {
        return jdbc.sql("select * from generation_flag where generation_id = :id order by recipe_id")
                .param("id", generationId)
                .query((ResultSet row, int number) -> new GenerationFlag(
                        row.getObject("generation_id", UUID.class),
                        row.getString("recipe_id"),
                        row.getString("reason"),
                        row.getTimestamp("flagged_at").toInstant()))
                .list();
    }

    private static ContributedRecipe map(ResultSet row, int number) throws SQLException {
        return new ContributedRecipe(
                row.getObject("id", UUID.class),
                row.getString("recipe_id"),
                row.getString("namespace"),
                row.getString("version"),
                row.getString("manifest"),
                row.getString("content"),
                row.getString("content_hash"),
                ContributedRecipeStatus.of(row.getString("status")),
                row.getObject("submitted_by", UUID.class),
                row.getTimestamp("submitted_at").toInstant(),
                row.getObject("reviewed_by", UUID.class),
                instant(row.getTimestamp("reviewed_at")),
                row.getObject("verified_by_run", UUID.class),
                row.getObject("revoked_by", UUID.class),
                instant(row.getTimestamp("revoked_at")),
                row.getString("revoked_reason"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
