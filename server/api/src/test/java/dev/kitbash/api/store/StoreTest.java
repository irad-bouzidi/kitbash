package dev.kitbash.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * The §10 tables, against a real Postgres.
 *
 * <p>What is worth testing here is not that a row round-trips — it is the behaviour the schema
 * encodes and the code cannot: the unique constraint that makes preset revisions safe under
 * concurrency, the {@code on delete set null} that keeps history when its preset is deleted, and
 * the partial index that makes verification dedupe correct rather than merely likely.
 *
 * <p>Those are all properties of the database. A fake would assert only that the fake agrees with
 * itself.
 */
class StoreTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private final PresetRepository presets = new PresetRepository(PostgresFixture.jdbc());
    private final GenerationRepository generations = new GenerationRepository(PostgresFixture.jdbc());
    private final ShareLinkRepository shareLinks = new ShareLinkRepository(PostgresFixture.jdbc());
    private final VerificationRunRepository runs = new VerificationRunRepository(PostgresFixture.jdbc());

    @BeforeEach
    void emptyTheTables() {
        PostgresFixture.clean();
    }

    @Nested
    @DisplayName("presets")
    class Presets {

        @Test
        @DisplayName("a preset round-trips, jsonb columns and nullable ones included")
        void roundTrips() {
            UUID owner = UUID.randomUUID();
            Preset saved = presets.insert(preset(owner, "my-stack", 1));

            Preset read = presets.findById(saved.id()).orElseThrow();

            assertThat(read.name()).isEqualTo("my-stack");
            assertThat(read.visibility()).isEqualTo(Visibility.PRIVATE);
            assertThat(read.versionPolicy()).isEqualTo(VersionPolicy.TRACK_LATEST);
            assertThat(read.pinnedRecipes()).isNull();
            assertThat(read.selection()).contains("\"projectName\"").contains("demo");
            assertThat(read.createdAt()).isEqualTo(NOW);
        }

        /**
         * The constraint is what makes {@link PresetRepository#nextRevision} safe. Two saves that
         * read the same revision number race, and the database refuses the loser instead of
         * letting both write revision 2 and leaving one of them unreachable.
         */
        @Test
        @DisplayName("the same owner, name and revision cannot be written twice")
        void refusesADuplicateRevision() {
            UUID owner = UUID.randomUUID();
            presets.insert(preset(owner, "my-stack", 1));

            assertThatThrownBy(() -> presets.insert(preset(owner, "my-stack", 1)))
                    .isInstanceOf(DuplicateKeyException.class);

            // A new revision of the same preset is exactly what is allowed.
            assertThat(presets.nextRevision(owner, "my-stack")).isEqualTo(2);
            presets.insert(preset(owner, "my-stack", 2));
            assertThat(presets.findByOwner(owner)).hasSize(2);
        }

        @Test
        @DisplayName("another owner may use the same name, because presets are per person")
        void namesAreScopedToTheirOwner() {
            presets.insert(preset(UUID.randomUUID(), "my-stack", 1));
            presets.insert(preset(UUID.randomUUID(), "my-stack", 1));

            assertThat(presets.nextRevision(UUID.randomUUID(), "my-stack")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("generations")
    class Generations {

        /**
         * §10 makes this {@code on delete set null} rather than a cascade on purpose: deleting a
         * preset must not delete the history of what it produced. The row outlives its origin, and
         * its lock still replays.
         */
        @Test
        @DisplayName("deleting a preset keeps the generations it produced, with a null preset id")
        void historySurvivesItsPreset() {
            UUID owner = UUID.randomUUID();
            Preset saved = presets.insert(preset(owner, "my-stack", 1));
            Generation generation = generations.insert(generation(owner, saved.id(), "demo", NOW));

            presets.deleteByOwnerAndName(owner, "my-stack");

            Generation read = generations.findById(generation.id()).orElseThrow();
            assertThat(read.presetId()).isNull();
            assertThat(read.lock()).contains("backend-spring-java");
            assertThat(read.projectName()).isEqualTo("demo");
        }

        @Test
        @DisplayName("history comes back newest first, which is the index's whole job")
        void historyIsNewestFirst() {
            UUID owner = UUID.randomUUID();
            generations.insert(generation(owner, null, "oldest", NOW.minus(2, ChronoUnit.DAYS)));
            generations.insert(generation(owner, null, "newest", NOW));
            generations.insert(generation(owner, null, "middle", NOW.minus(1, ChronoUnit.DAYS)));
            generations.insert(generation(UUID.randomUUID(), null, "somebody-else", NOW));

            assertThat(generations.historyFor(owner, 10))
                    .extracting(Generation::projectName)
                    .containsExactly("newest", "middle", "oldest");
        }

        @Test
        @DisplayName("the same selection is findable across users, which is what prioritises the matrix")
        void findsBySelectionHash() {
            generations.insert(generation(UUID.randomUUID(), null, "one", NOW));
            generations.insert(generation(UUID.randomUUID(), null, "two", NOW));

            assertThat(generations.findBySelectionHash("sha256:selection")).hasSize(2);
            assertThat(generations.findBySelectionHash("sha256:something-else")).isEmpty();
        }

        /**
         * §10 expresses Keep as clearing {@code expires_at} rather than as a second retention
         * policy, so one sentence covers all three stores: anything older than a month is gone
         * unless you kept it.
         */
        @Test
        @DisplayName("keeping a row clears its expiry rather than inventing a second policy")
        void keepingClearsTheExpiry() {
            Generation generation = generations.insert(generation(UUID.randomUUID(), null, "demo", NOW));
            assertThat(generation.expiresAt()).isNotNull();

            generations.keep(generation.id());

            Generation kept = generations.findById(generation.id()).orElseThrow();
            assertThat(kept.kept()).isTrue();
            assertThat(kept.expiresAt()).isNull();
        }

        @Test
        @DisplayName("an expired artifact leaves the row, because the lock is the valuable part")
        void artifactExpiresWithoutTheRow() {
            Generation generation = generations.insert(generation(UUID.randomUUID(), null, "demo", NOW));

            generations.releaseArtifact(generation.id());

            Generation read = generations.findById(generation.id()).orElseThrow();
            assertThat(read.artifactKey()).isNull();
            assertThat(read.lock()).isNotNull();
        }
    }

    @Nested
    @DisplayName("share links")
    class ShareLinks {

        @Test
        @DisplayName("a token round-trips, and an unexpiring link is a null rather than a far future")
        void roundTrips() {
            shareLinks.insert(new ShareLink("tok_abc123", selectionJson(), NOW, null));

            ShareLink read = shareLinks.find("tok_abc123").orElseThrow();

            assertThat(read.selection()).contains("demo");
            assertThat(read.expiresAt()).isNull();
            assertThat(shareLinks.find("tok_nothing")).isEmpty();
        }
    }

    @Nested
    @DisplayName("verification runs")
    class VerificationRuns {

        /**
         * The reason §10 specifies a partial unique index rather than a check-then-insert. Two
         * requests for the same selection and catalog must produce one run: the second claim reads
         * back the first rather than starting a second container's worth of work.
         */
        @Test
        @DisplayName("two claims on the same selection and catalog produce one run")
        void dedupes() {
            VerificationRun first = runs.claim(run(VerificationStatus.PENDING));
            VerificationRun second = runs.claim(run(VerificationStatus.PENDING));

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(countRuns()).isEqualTo(1);
        }

        @Test
        @DisplayName("a run that passed still dedupes, because re-running it would prove nothing new")
        void aPassedRunBlocksAnother() {
            VerificationRun first = runs.claim(run(VerificationStatus.PENDING));
            runs.finish(first.id(), VerificationStatus.PASSED, "logs/first.txt", NOW, NOW.plus(Duration.ofDays(30)));

            assertThat(runs.claim(run(VerificationStatus.PENDING)).id()).isEqualTo(first.id());
            assertThat(countRuns()).isEqualTo(1);
        }

        /**
         * And the other half, which is why the index is partial: a failure is a result somebody
         * may want to reproduce once the recipe is fixed, so it must not block a retry forever.
         */
        @Test
        @DisplayName("a failed run releases the dedupe, so the same selection can be retried")
        void aFailedRunCanBeRetried() {
            VerificationRun first = runs.claim(run(VerificationStatus.PENDING));
            runs.finish(first.id(), VerificationStatus.FAILED, "logs/first.txt", NOW, null);

            VerificationRun retry = runs.claim(run(VerificationStatus.PENDING));

            assertThat(retry.id()).isNotEqualTo(first.id());
            assertThat(countRuns()).isEqualTo(2);
        }

        @Test
        @DisplayName("a different catalog is a different run, because that is what is being verified")
        void anotherCatalogIsAnotherRun() {
            runs.claim(run(VerificationStatus.PENDING));

            VerificationRun other = runs.claim(new VerificationRun(
                    UUID.randomUUID(),
                    null,
                    selectionJson(),
                    lockJson(),
                    "sha256:selection",
                    "sha256:a-newer-catalog",
                    VerificationStatus.PENDING,
                    null,
                    null,
                    null,
                    null));

            assertThat(countRuns()).isEqualTo(2);
            assertThat(other.catalogDigest()).isEqualTo("sha256:a-newer-catalog");
        }

        @Test
        @DisplayName("a run moves pending → running → passed, keeping its log and its times")
        void recordsTheLifecycle() {
            VerificationRun claimed = runs.claim(run(VerificationStatus.PENDING));

            runs.markRunning(claimed.id(), NOW);
            runs.finish(
                    claimed.id(),
                    VerificationStatus.PASSED,
                    "logs/full-stack.txt",
                    NOW.plus(Duration.ofMinutes(4)),
                    NOW.plus(Duration.ofDays(30)));

            VerificationRun read = runs.findById(claimed.id()).orElseThrow();
            assertThat(read.status()).isEqualTo(VerificationStatus.PASSED);
            assertThat(read.logKey()).isEqualTo("logs/full-stack.txt");
            assertThat(read.startedAt()).isEqualTo(NOW);
            assertThat(read.finishedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        }

        @Test
        @DisplayName("a matrix run has no requester, which is how it is told from somebody waiting")
        void aMatrixRunHasNoRequester() {
            assertThat(runs.claim(run(VerificationStatus.PENDING)).requestedBy())
                    .isNull();
        }

        private int countRuns() {
            return PostgresFixture.jdbc()
                    .sql("select count(*) from verification_run")
                    .query(Integer.class)
                    .single();
        }
    }

    // --- fixtures ----------------------------------------------------------

    private static Preset preset(UUID owner, String name, int revision) {
        return new Preset(
                UUID.randomUUID(),
                owner,
                name,
                "The stack I always start from",
                Visibility.PRIVATE,
                selectionJson(),
                VersionPolicy.TRACK_LATEST,
                null,
                revision,
                NOW,
                NOW);
    }

    private static Generation generation(UUID owner, UUID presetId, String projectName, Instant createdAt) {
        return new Generation(
                UUID.randomUUID(),
                owner,
                presetId,
                projectName,
                selectionJson(),
                lockJson(),
                "sha256:catalog",
                "sha256:selection",
                "artifacts/" + projectName + ".zip",
                GenerationStatus.SUCCEEDED,
                412,
                163029,
                createdAt,
                createdAt.plus(Duration.ofDays(30)),
                false,
                null);
    }

    private static VerificationRun run(VerificationStatus status) {
        return new VerificationRun(
                UUID.randomUUID(),
                null,
                selectionJson(),
                lockJson(),
                "sha256:selection",
                "sha256:catalog",
                status,
                null,
                null,
                null,
                null);
    }

    private static String selectionJson() {
        return """
               {"schemaVersion":1,"projectName":"demo","options":{"backend":"backend-spring-java"}}""";
    }

    private static String lockJson() {
        return """
               {"backend-spring-java":"1.4.0","base":"1.0.0"}""";
    }
}
