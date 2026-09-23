package dev.kitbash.api.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.api.store.VerificationRun;
import dev.kitbash.api.store.VerificationRunRepository;
import dev.kitbash.api.store.VerificationStatus;
import dev.kitbash.core.lock.Lock;
import dev.kitbash.core.selection.Selection;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who gets a container, and who gets somebody else's answer (§12, kitbash-37).
 *
 * <p>§12 says on-demand verification is affordable for every authenticated user <em>not because it
 * is cheap, but because the work is deduplicated</em>. That sentence is this class: a run is keyed
 * by {@code (selection_hash, catalog_digest)}, so the second person to ask about the house stack
 * gets the first person's result without a container starting, and the nightly matrix has already
 * answered every enumerated combination before anyone asks. What is left to pay for is the
 * genuinely novel selection, which is the only case worth a container.
 *
 * <p>The dedupe is the database's, not this code's. Checking for an existing run and then inserting
 * is a race whose window is wide enough to lose under ordinary load, so the claim is a single
 * statement against the partial unique index from §10 and the loser reads back the winner's row.
 */
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final VerificationRunRepository runs;
    private final VerificationWorkers workers;
    private final VerificationRunner runner;
    private final VerificationLogs logs;
    /**
     * The digest of the catalog this process loaded, as a supplier.
     *
     * <p>A supplier rather than the {@link dev.kitbash.core.recipe.Catalog} itself because the
     * digest is all this needs, and depending on the whole catalog would mean a test about who gets
     * a container had to build one.
     */
    private final Supplier<String> catalogDigest;

    private final Clock clock;

    public VerificationService(
            VerificationRunRepository runs,
            VerificationWorkers workers,
            VerificationRunner runner,
            VerificationLogs logs,
            Supplier<String> catalogDigest,
            Clock clock) {
        this.runs = runs;
        this.workers = workers;
        this.runner = runner;
        this.logs = logs;
        this.catalogDigest = catalogDigest;
        this.clock = clock;
    }

    /** Whether a run had to be started, or one already existed — the difference between 202 and 200. */
    public record Claim(VerificationRun run, boolean started) {}

    /**
     * The run for this selection, starting one only if nothing already answers the question.
     *
     * <p>The fast path is checked before the pool is touched, because the common case is a
     * selection the nightly already built and the expensive thing about it would be queueing behind
     * somebody else's container to be told so.
     */
    public Claim verify(Selection selection, Lock lock, UUID owner) {
        String hash = selection.hash();
        String digest = catalogDigest.get();

        Optional<VerificationRun> answered = runs.active(hash, digest);
        if (answered.isPresent()) {
            return new Claim(answered.get(), false);
        }

        UUID id = UUID.randomUUID();
        VerificationRun requested = new VerificationRun(
                id,
                owner,
                selection.canonicalJson(),
                serialise(lock),
                hash,
                digest,
                VerificationStatus.PENDING,
                null,
                null,
                null,
                null);

        VerificationRun claimed = runs.claim(requested);
        if (!claimed.id().equals(id)) {
            // Somebody else's insert won the race between the lookup above and this statement.
            // Their run is the answer to this request, and no container starts for it.
            return new Claim(claimed, false);
        }

        try {
            workers.submit(owner, id, () -> execute(claimed, selection));
        } catch (AlreadyVerifyingException | QueueFullException refused) {
            // The row was claimed and nothing will run it. Finishing it as `failed` is what takes
            // it out of the partial index; leaving it `pending` would make one refused request
            // block this selection for everybody, permanently, with no run to wait for.
            runs.finish(id, VerificationStatus.FAILED, null, clock.instant(), expiry());
            throw refused;
        }

        return new Claim(claimed, true);
    }

    public Optional<VerificationRun> find(UUID id) {
        return runs.findById(id);
    }

    /** The log of a finished run, when the store still has it (§10's thirty days). */
    public Optional<String> logOf(VerificationRun run) {
        return Optional.ofNullable(run.logKey()).flatMap(logs::find);
    }

    /**
     * One run, in a worker thread.
     *
     * <p>Every exit finishes the row. A run left {@code running} because something threw would sit
     * in the dedupe index forever, and every later request for that selection would be told to wait
     * for a container that stopped existing.
     */
    private void execute(VerificationRun run, Selection selection) {
        runs.markRunning(run.id(), clock.instant());
        Instant deadline =
                clock.instant().plus(Duration.ofMinutes(workers.properties().timeoutMinutes()));

        VerificationRunner.Outcome result;
        try {
            result = runner.run(run.id().toString(), envelopeOf(selection), deadline);
        } catch (RuntimeException broken) {
            // The runner failed rather than the project — no image, no daemon, no disk. That is
            // the deployment's problem and not the caller's selection, but the caller still needs
            // an answer, and `failed` with the reason in the log is the honest one.
            log.error("Verification run {} could not be executed", run.id(), broken);
            String key = logs.put(run.id().toString(), "The verification runner could not start: " + broken);
            runs.finish(run.id(), VerificationStatus.FAILED, key, clock.instant(), expiry());
            return;
        }

        String key = logs.put(run.id().toString(), result.log());
        runs.finish(
                run.id(),
                result.passed() ? VerificationStatus.PASSED : VerificationStatus.FAILED,
                key,
                clock.instant(),
                expiry());
    }

    /** §10's uniform thirty days, applied to the row as well as to the object behind it. */
    private Instant expiry() {
        return clock.instant().plus(Duration.ofDays(30));
    }

    private static JsonNode envelopeOf(Selection selection) {
        return JSON.valueToTree(selection.toEnvelope());
    }

    /**
     * The lock, as the column wants it.
     *
     * <p>§7 calls the lock the valuable part and §10 keeps it beside every row that produced one.
     * A verification's lock is what makes its verdict readable a month later: "this combination
     * built" is worth little without the versions it built against, and the catalog digest alone
     * does not say which recipes were in play.
     */
    private static String serialise(Lock lock) {
        try {
            return JSON.writeValueAsString(lock);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("A lock that cannot be serialised is a bug, not a request problem", e);
        }
    }
}
