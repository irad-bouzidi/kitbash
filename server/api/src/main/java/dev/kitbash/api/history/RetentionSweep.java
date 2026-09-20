package dev.kitbash.api.history;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thirty days, uniformly (§10, §18).
 *
 * <p>One number across cached zips, generation rows and verification logs, because <i>"anything
 * older than a month is gone unless you kept it"</i> is a sentence people can remember and three
 * separate policies is three things nobody can recall.
 *
 * <p>Nothing reproducible is lost at expiry. The lock is the valuable part and it is small enough
 * to keep — what goes is the artifact, which can be made again because rendering is deterministic
 * (§4). That is the whole reason this is a sweep rather than an agonising decision.
 *
 * <p>Two halves, split by who can enforce them: the object store expires zips with a lifecycle
 * rule, and this expires rows. A job that tried to do both would be duplicating a policy the
 * store already applies, and drifting from it.
 */
@Component
@Profile("persistence")
public class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    /**
     * How long a <i>kept</i> row holds on to its artifact.
     *
     * <p>§10 allows expiring an artifact while keeping the row, and says the row then replays by
     * re-rendering. That is documented behaviour rather than a degradation: the lock is what makes
     * the row valuable, and a year-old zip nobody has downloaded is storage rather than a record.
     */
    static final Duration KEPT_ARTIFACT_LIFETIME = Duration.ofDays(365);

    private final JdbcClient jdbc;

    public RetentionSweep(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Nightly, at 03:00 — an hour after the verification matrix, so the two do not compete for a
     * database connection pool on a small deployment.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void nightly() {
        sweep(Instant.now());
    }

    /**
     * One pass. Idempotent by construction: every statement is bounded by a timestamp comparison,
     * so running it twice does the work once and running it after a crash resumes rather than
     * repeats.
     *
     * <p>Counts are logged by category, because a retention policy nobody can observe is a
     * retention policy nobody can tell has stopped running.
     */
    public Swept sweep(Instant now) {
        int expiredGenerations = jdbc.sql(
                        """
                        delete from generation
                        where kept = false and expires_at is not null and expires_at < :now
                        """)
                .param("now", java.sql.Timestamp.from(now))
                .update();

        int releasedArtifacts = jdbc.sql(
                        """
                        update generation
                        set artifact_key = null
                        where kept = true and artifact_key is not null and created_at < :cutoff
                        """)
                .param("cutoff", java.sql.Timestamp.from(now.minus(KEPT_ARTIFACT_LIFETIME)))
                .update();

        int expiredShareLinks = jdbc.sql("delete from share_link where expires_at is not null and expires_at < :now")
                .param("now", java.sql.Timestamp.from(now))
                .update();

        // kitbash-37 stores the logs themselves; this owns when their rows stop being true.
        int expiredVerifications = jdbc.sql(
                        "delete from verification_run where expires_at is not null and expires_at < :now")
                .param("now", java.sql.Timestamp.from(now))
                .update();

        Swept swept = new Swept(expiredGenerations, releasedArtifacts, expiredShareLinks, expiredVerifications);
        log.info(
                "Retention sweep: generations={} artifactsReleased={} shareLinks={} verificationRuns={}",
                swept.generations(),
                swept.artifactsReleased(),
                swept.shareLinks(),
                swept.verificationRuns());
        return swept;
    }

    /** What one pass removed, by category. */
    public record Swept(int generations, int artifactsReleased, int shareLinks, int verificationRuns) {

        public int total() {
            return generations + artifactsReleased + shareLinks + verificationRuns;
        }
    }
}
