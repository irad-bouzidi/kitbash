package dev.kitbash.api.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Thirty days, uniformly (§10, §18, §27).
 *
 * <p>§27 asks for the sweep to be proven by a test that ages rows and asserts what survives, and
 * the surviving half is the interesting one: a kept row stays, a kept row's <i>artifact</i> is
 * eventually released anyway, and the row still replays afterwards because the lock is what makes
 * it valuable.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "test"})
class RetentionSweepTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");

    @Autowired
    private RetentionSweep sweep;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyTheTables() {
        jdbc.sql("truncate generation, share_link, verification_run cascade").update();
    }

    private UUID generation(Instant createdAt, Instant expiresAt, boolean kept, String artifactKey) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                        insert into generation (id, owner_id, project_name, selection, lock, catalog_digest,
                                                selection_hash, artifact_key, status, created_at, expires_at, kept)
                        values (:id, gen_random_uuid(), 'demo', '{}'::jsonb, '{"base":"1.0.0"}'::jsonb,
                                'sha256:catalog', 'sha256:selection', :artifactKey, 'succeeded',
                                :createdAt, :expiresAt, :kept)
                        """)
                .param("id", id)
                .param("artifactKey", artifactKey)
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .param("expiresAt", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt))
                .param("kept", kept)
                .update();
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.sql("select count(*) from generation where id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                == 1;
    }

    @Test
    @DisplayName("what is past its expiry goes, and what is not stays")
    void expiresTheExpired() {
        UUID old = generation(NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(1)), false, "a/key");
        UUID recent = generation(NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(28)), false, "b/key");

        RetentionSweep.Swept swept = sweep.sweep(NOW);

        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue();
        assertThat(swept.generations()).isEqualTo(1);
    }

    /**
     * §10: Keep clears {@code expires_at} rather than inventing a second policy, so a kept row has
     * nothing for the sweep to compare against and simply stays.
     */
    @Test
    @DisplayName("a kept row stays however old it is")
    void keptRowsSurvive() {
        UUID kept = generation(NOW.minus(Duration.ofDays(400)), null, true, "kept/key");

        sweep.sweep(NOW);

        assertThat(exists(kept)).isTrue();
    }

    /**
     * §10 allows expiring a kept row's artifact after a year, and says the row then replays by
     * re-rendering. Documented behaviour rather than a degradation: the lock is the valuable part,
     * and rendering is deterministic (§4), so nothing reproducible was lost.
     */
    @Test
    @DisplayName("a kept row's artifact is released after a year, and the row survives to re-render")
    void keptArtifactsAreReleasedEventually() {
        UUID ancient = generation(NOW.minus(Duration.ofDays(400)), null, true, "ancient/key");
        UUID lastMonth = generation(NOW.minus(Duration.ofDays(31)), null, true, "recent/key");

        RetentionSweep.Swept swept = sweep.sweep(NOW);

        assertThat(exists(ancient)).isTrue();
        assertThat(artifactKeyOf(ancient)).isNull();
        // And the lock, which is what makes the row still worth having, is untouched.
        assertThat(lockOf(ancient)).contains("base");

        assertThat(artifactKeyOf(lastMonth)).isEqualTo("recent/key");
        assertThat(swept.artifactsReleased()).isEqualTo(1);
    }

    @Test
    @DisplayName("expired share links and verification runs go the same way, on the same number")
    void expiresTheOtherTables() {
        jdbc.sql(
                        """
                        insert into share_link (token, selection, created_at, expires_at)
                        values ('oldtoken001', '{}'::jsonb, :created, :expired),
                               ('livetoken01', '{}'::jsonb, :created, :future)
                        """)
                .param("created", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(40))))
                .param("expired", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(1))))
                .param("future", java.sql.Timestamp.from(NOW.plus(Duration.ofDays(1))))
                .update();

        jdbc.sql(
                        """
                        insert into verification_run (id, selection, lock, selection_hash, catalog_digest,
                                                      status, expires_at)
                        values (gen_random_uuid(), '{}'::jsonb, '{}'::jsonb, 'sha256:a', 'sha256:c',
                                'failed', :expired)
                        """)
                .param("expired", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(1))))
                .update();

        RetentionSweep.Swept swept = sweep.sweep(NOW);

        assertThat(swept.shareLinks()).isEqualTo(1);
        assertThat(swept.verificationRuns()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from share_link")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    /**
     * §27 asks for the sweep to be idempotent, and the reason is operational: a job that cannot be
     * run twice is a job nobody dares re-run after a crash.
     */
    @Test
    @DisplayName("running it twice does the work once")
    void isIdempotent() {
        generation(NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(1)), false, "a/key");
        generation(NOW.minus(Duration.ofDays(400)), null, true, "ancient/key");

        RetentionSweep.Swept first = sweep.sweep(NOW);
        RetentionSweep.Swept second = sweep.sweep(NOW);

        assertThat(first.total()).isEqualTo(2);
        assertThat(second.total())
                .as("the second pass repeated the first's work")
                .isZero();
    }

    private String artifactKeyOf(UUID id) {
        return jdbc.sql("select artifact_key from generation where id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String lockOf(UUID id) {
        return jdbc.sql("select lock::text from generation where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }
}
