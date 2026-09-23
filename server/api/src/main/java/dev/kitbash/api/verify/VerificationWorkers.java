package dev.kitbash.api.verify;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The bounded machine behind {@code POST /verify} (§12, §13).
 *
 * <p>Three limits, and each is here rather than in the controller because each is a property of the
 * pool rather than of a request: four runs in containers at once, one in flight per caller, and a
 * queue that refuses rather than grows.
 *
 * <p>The queue is bounded on purpose. An unbounded one turns "the machine is busy" into "your
 * request is accepted and will be looked at in an hour", which is the same outcome delivered
 * dishonestly — the caller waits on a spinner instead of deciding. So a full queue is a
 * {@link QueueFullException} carrying the depth, and §14 turns that into a 429 with a number in it.
 *
 * <p>Per-caller concurrency is what makes the whole thing open to everyone (§18). The expensive
 * case is one person asking many <em>different</em> questions; the same person asking the same one
 * twice is free, because the dedupe index answered it before this class ever saw it.
 */
public class VerificationWorkers implements AutoCloseable {

    private final VerificationProperties properties;
    private final ThreadPoolExecutor pool;

    /**
     * Who has runs in flight, and which ones. Cleared as each finishes, however it finishes.
     *
     * <p>A set rather than a single id because the limit is a number, and a number of one is a
     * configuration choice rather than a shape. Keeping the ids means the refusal can name a run
     * the caller already has instead of only asserting that they have one.
     */
    private final Map<UUID, Set<UUID>> inFlight = new ConcurrentHashMap<>();

    public VerificationWorkers(VerificationProperties properties) {
        this.properties = properties;
        this.pool = new ThreadPoolExecutor(
                properties.workers(),
                properties.workers(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueDepth()),
                runnable -> {
                    Thread thread = new Thread(runnable, "verify-worker");
                    // A daemon, so a run in progress cannot hold a shutdown open for fifteen
                    // minutes. The row it belongs to stays `running` and is swept; a container
                    // that outlives the process is removed by `--rm` when it exits.
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Takes a run, or says why not.
     *
     * <p>The claim on the caller's slot is made before the submission and released if the
     * submission is refused — the other order would leave a caller marked busy by a run that never
     * started, and the only way out of that would be waiting for something that is not running.
     *
     * @throws AlreadyVerifyingException when this caller already has one going
     * @throws QueueFullException when the queue is at its depth
     */
    public void submit(UUID owner, UUID runId, Runnable work) {
        UUID[] alreadyRunning = new UUID[1];
        inFlight.compute(owner, (key, mine) -> {
            Set<UUID> updated = mine == null ? ConcurrentHashMap.newKeySet() : mine;
            if (updated.size() >= properties.perUser() && !updated.contains(runId)) {
                alreadyRunning[0] = updated.iterator().next();
                return updated;
            }
            updated.add(runId);
            return updated;
        });
        if (alreadyRunning[0] != null) {
            throw new AlreadyVerifyingException(alreadyRunning[0]);
        }
        try {
            pool.execute(() -> {
                try {
                    work.run();
                } finally {
                    release(owner, runId);
                }
            });
        } catch (RejectedExecutionException full) {
            release(owner, runId);
            throw new QueueFullException(queueDepth());
        }
    }

    private void release(UUID owner, UUID runId) {
        inFlight.computeIfPresent(owner, (key, mine) -> {
            mine.remove(runId);
            // The empty set goes with the last run, so a busy afternoon does not leave one entry
            // per person who ever asked.
            return mine.isEmpty() ? null : mine;
        });
    }

    /** How many runs are waiting for a worker — the number a refused caller is told. */
    public int queueDepth() {
        return pool.getQueue().size();
    }

    /** How many are in containers right now. */
    public int running() {
        return pool.getActiveCount();
    }

    public VerificationProperties properties() {
        return properties;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
