package dev.kitbash.api.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kitbash.api.store.PostgresFixture;
import dev.kitbash.api.store.VerificationRun;
import dev.kitbash.api.store.VerificationRunRepository;
import dev.kitbash.api.store.VerificationStatus;
import dev.kitbash.core.lock.Lock;
import dev.kitbash.core.selection.OptionValue;
import dev.kitbash.core.selection.Selection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §12's claim, tested: verification is open to everyone <em>because the work is deduplicated</em>.
 *
 * <p>Against a real Postgres, because the dedupe is the database's. The partial unique index is
 * what makes two simultaneous requests produce one run; a fake repository would assert only that
 * the fake agrees with itself, and the race this exists to survive happens between a lookup and an
 * insert that a fake performs atomically by accident.
 *
 * <p>The runner is a fake, and that is the right seam. What a container does to a generated project
 * is proved by the matrix, ninety-eight cells at a time. What is only provable here is who gets one.
 */
class VerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DIGEST = "sha256:catalog";

    /** An empty lock: what is under test is who gets a container, not what they build against. */
    private static final Lock LOCK = new Lock(Map.of(), DIGEST);

    private final VerificationRunRepository runs = new VerificationRunRepository(PostgresFixture.jdbc());
    private final RecordingRunner runner = new RecordingRunner();
    private final VerificationLogs logs = new VerificationLogs.InMemory();

    @BeforeEach
    void emptyTheTables() {
        PostgresFixture.clean();
    }

    private VerificationService service(VerificationWorkers workers) {
        return new VerificationService(
                runs, workers, runner, logs, () -> DIGEST, CLOCK, new VerificationMetrics(new SimpleMeterRegistry()));
    }

    private static VerificationWorkers workers(int poolSize, int perUser, int queueDepth) {
        return new VerificationWorkers(new VerificationProperties(poolSize, perUser, queueDepth, 15));
    }

    private static Selection selection(String projectName) {
        return Selection.of(
                projectName,
                Map.of("backend", new OptionValue.Text("backend-spring-java")),
                Map.of("groupId", "com.example"));
    }

    @Nested
    @DisplayName("dedupe")
    class Dedupe {

        @Test
        @DisplayName("two people asking the same question produce one container run and two results")
        void deduplicates() throws Exception {
            runner.hold();
            try (VerificationWorkers workers = workers(2, 1, 8)) {
                VerificationService service = service(workers);
                Selection same = selection("house-stack");

                VerificationService.Claim first = service.verify(same, LOCK, UUID.randomUUID());
                VerificationService.Claim second = service.verify(same, LOCK, UUID.randomUUID());

                assertThat(first.started())
                        .as("the first caller starts the run")
                        .isTrue();
                assertThat(second.started())
                        .as("the second is handed the first caller's run, not a container")
                        .isFalse();
                assertThat(second.run().id()).isEqualTo(first.run().id());

                runner.release();
                runner.awaitRuns(1);
                assertThat(runner.ids()).as("exactly one container ran").hasSize(1);
            }
        }

        @Test
        @DisplayName("a failed run does not block a retry, and a passed one does")
        void failureReleasesTheDedupe() throws Exception {
            try (VerificationWorkers workers = workers(1, 1, 8)) {
                VerificationService service = service(workers);
                Selection same = selection("retryable");

                runner.answer(false);
                UUID firstId =
                        service.verify(same, LOCK, UUID.randomUUID()).run().id();
                runner.awaitRuns(1);
                assertThat(statusOf(firstId)).isEqualTo(VerificationStatus.FAILED);

                // The asymmetry §10's index encodes: `failed` is outside it on purpose, because a
                // failure is a result somebody may want to reproduce once the recipe is fixed.
                runner.answer(true);
                VerificationService.Claim retry = service.verify(same, LOCK, UUID.randomUUID());
                assertThat(retry.started()).as("a failure is retryable").isTrue();
                assertThat(retry.run().id()).isNotEqualTo(firstId);
                runner.awaitRuns(2);

                // And a pass is not: the question has an answer, so asking again is free.
                VerificationService.Claim third = service.verify(same, LOCK, UUID.randomUUID());
                assertThat(third.started())
                        .as("a passed run answers the question for everybody")
                        .isFalse();
                assertThat(runner.ids()).hasSize(2);
            }
        }
    }

    @Nested
    @DisplayName("controls")
    class Controls {

        @Test
        @DisplayName("a queue flood is refused with the depth, rather than queued for an hour")
        void refusesAFloodWithTheDepth() throws Exception {
            runner.hold();
            // One worker, one waiting: the third distinct selection has nowhere to go.
            try (VerificationWorkers workers = workers(1, 8, 1)) {
                VerificationService service = service(workers);
                UUID caller = UUID.randomUUID();

                service.verify(selection("one"), LOCK, caller);
                service.verify(selection("two"), LOCK, caller);

                assertThatThrownBy(() -> service.verify(selection("three"), LOCK, caller))
                        .isInstanceOf(QueueFullException.class)
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(QueueFullException.class))
                        .extracting(QueueFullException::depth)
                        .as("the caller is told how many are ahead of them")
                        .isEqualTo(1);

                runner.release();
            }
        }

        @Test
        @DisplayName("a refused request leaves nothing blocking the selection it asked about")
        void aRefusalDoesNotPoisonTheSelection() throws Exception {
            runner.hold();
            try (VerificationWorkers workers = workers(1, 8, 1)) {
                VerificationService service = service(workers);
                UUID caller = UUID.randomUUID();
                Selection refused = selection("three");

                service.verify(selection("one"), LOCK, caller);
                service.verify(selection("two"), LOCK, caller);
                assertThatThrownBy(() -> service.verify(refused, LOCK, caller)).isInstanceOf(QueueFullException.class);

                // The claimed row was finished as `failed`, so it left the partial index. Leaving
                // it `pending` would block this selection for everybody, for ever, with no run to
                // wait for — a refusal that quietly becomes a permanent one.
                assertThat(runs.active(refused.hash(), DIGEST))
                        .as("nothing in the dedupe index answers for a run that never started")
                        .isEmpty();

                runner.release();
            }
        }

        @Test
        @DisplayName("one run at a time per person, and the refusal names the one they have")
        void onePerPerson() throws Exception {
            runner.hold();
            try (VerificationWorkers workers = workers(4, 1, 8)) {
                VerificationService service = service(workers);
                UUID caller = UUID.randomUUID();

                UUID mine =
                        service.verify(selection("mine"), LOCK, caller).run().id();

                assertThatThrownBy(() -> service.verify(selection("also-mine"), LOCK, caller))
                        .isInstanceOf(AlreadyVerifyingException.class)
                        .asInstanceOf(
                                org.assertj.core.api.InstanceOfAssertFactories.type(AlreadyVerifyingException.class))
                        .extracting(AlreadyVerifyingException::inFlight)
                        .as("the useful answer is which run they already have")
                        .isEqualTo(mine);

                // Somebody else is unaffected: the limit is per person, not on the pool.
                assertThat(service.verify(selection("theirs"), LOCK, UUID.randomUUID())
                                .started())
                        .isTrue();

                runner.release();
            }
        }

        @Test
        @DisplayName("the run is given the deadline §12 promises, not the pool's own timeout")
        void passesTheDeadline() throws Exception {
            try (VerificationWorkers workers = workers(1, 1, 8)) {
                service(workers).verify(selection("timed"), LOCK, UUID.randomUUID());
                runner.awaitRuns(1);

                assertThat(runner.deadlines()).singleElement().isEqualTo(NOW.plus(Duration.ofMinutes(15)));
            }
        }
    }

    @Nested
    @DisplayName("finishing")
    class Finishing {

        @Test
        @DisplayName("a runner that cannot start still finishes the row, rather than leaving it running")
        void aBrokenRunnerStillFinishesTheRow() throws Exception {
            runner.breakDown();
            try (VerificationWorkers workers = workers(1, 1, 8)) {
                UUID id = service(workers)
                        .verify(selection("no-daemon"), LOCK, UUID.randomUUID())
                        .run()
                        .id();
                runner.awaitRuns(1);

                // A row left `running` sits in the dedupe index for ever, and every later request
                // for that selection is told to wait for a container that stopped existing.
                assertThat(statusOf(id)).isEqualTo(VerificationStatus.FAILED);
            }
        }

        @Test
        @DisplayName("the log is stored and served back by the key the row remembers")
        void storesTheLog() throws Exception {
            runner.answer(true);
            try (VerificationWorkers workers = workers(1, 1, 8)) {
                VerificationService service = service(workers);
                UUID id = service.verify(selection("logged"), LOCK, UUID.randomUUID())
                        .run()
                        .id();
                runner.awaitRuns(1);

                VerificationRun finished = runs.findById(id).orElseThrow();
                assertThat(service.logOf(finished)).contains(RecordingRunner.LOG);
            }
        }
    }

    private VerificationStatus statusOf(UUID id) {
        return runs.findById(id).orElseThrow().status();
    }

    /**
     * A runner that records rather than builds, and can be held mid-run.
     *
     * <p>Holding is what makes the concurrency tests deterministic: a run that returns immediately
     * is never "in flight" long enough for a second request to collide with it, and a test that
     * relied on winning that race would be a test that passes on a fast machine.
     */
    private static final class RecordingRunner implements VerificationRunner {

        static final String LOG = "the fake runner ran";

        private final List<String> ids = new CopyOnWriteArrayList<>();
        private final List<Instant> deadlines = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch gate;
        private volatile boolean passes = true;
        private volatile boolean broken;
        private final java.util.concurrent.atomic.AtomicInteger finished =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Outcome run(String runId, JsonNode envelope, Instant deadline) {
            ids.add(runId);
            deadlines.add(deadline);
            CountDownLatch held = gate;
            if (held != null) {
                try {
                    if (!held.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the fake runner was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                if (broken) {
                    throw new IllegalStateException("no docker daemon");
                }
                return new Outcome(passes, LOG, passes ? null : "jvm step");
            } finally {
                finished.incrementAndGet();
            }
        }

        void hold() {
            gate = new CountDownLatch(1);
        }

        void release() {
            CountDownLatch held = gate;
            gate = null;
            if (held != null) {
                held.countDown();
            }
        }

        void answer(boolean passes) {
            this.passes = passes;
        }

        void breakDown() {
            this.broken = true;
        }

        /**
         * Waits until this many runs have finished <em>and</em> their rows have been written.
         *
         * <p>Counting total runs rather than consuming permits, because the tests ask twice: a
         * second wait for "two by now" must not demand two more.
         */
        void awaitRuns(int total) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (finished.get() < total) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("only " + finished.get() + " run(s) finished, wanted " + total);
                }
                Thread.sleep(20);
            }
            // The row is written after the runner returns, so give the worker its moment.
            Thread.sleep(300);
        }

        List<String> ids() {
            return List.copyOf(ids);
        }

        List<Instant> deadlines() {
            return List.copyOf(deadlines);
        }
    }
}
