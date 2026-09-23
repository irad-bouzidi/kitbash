package dev.kitbash.api.generate;

import dev.kitbash.api.store.GenerationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Which selections people actually generate (§10, §27).
 *
 * <p>This exists for {@code kitbash-40}, which feeds it into the nightly matrix: the combinations
 * worth verifying every night are the ones people use, and the only honest source for that is what
 * they generated. Guessing produces a matrix that tests the stacks somebody imagined.
 *
 * <p>Tagged by <b>selection hash</b> and nothing else. §10 keeps project and package names out of
 * metrics for the same reason it keeps them out of logs — a metric is shipped somewhere else and
 * kept on somebody else's retention schedule — and a hash is exactly as useful for ranking.
 *
 * <p>Bounded, because a metric tag is a time series and an unbounded set of them is how a
 * monitoring bill becomes a postmortem. Past the bound the hash is dropped rather than the counter:
 * a selection generated once is not a popular selection, so losing it costs nothing.
 */
@Component
public class PopularSelections {

    /**
     * How many distinct selections get their own series.
     *
     * <p>A team has a house stack and a handful of variations. Two hundred is generous for that
     * and small enough that the cardinality is a number rather than a risk.
     */
    private static final int TRACKED = 200;

    private final MeterRegistry meters;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Counter overflow;

    /**
     * The durable half, when there is a database.
     *
     * <p>The counters above are this process's, and they start at zero every deploy — which is
     * right for a dashboard and useless for a nightly that has to decide what to build tonight.
     * §40 asks that there be <b>one definition of popular</b>, so both halves live behind this
     * class: the metric answers "what is being generated now", {@link #top(int)} answers "what has
     * been generated", and neither is a second ad-hoc query somewhere else.
     */
    private final ObjectProvider<GenerationRepository> history;

    public PopularSelections(MeterRegistry meters, ObjectProvider<GenerationRepository> history) {
        this.meters = meters;
        this.history = history;
        this.overflow = Counter.builder("kitbash.generations.byselection")
                .tag("selection", "other")
                .description("Generations of selections beyond the tracked set")
                .register(meters);
    }

    /** Counts one generation of this selection. */
    public void record(String selectionHash) {
        if (selectionHash == null || selectionHash.isBlank()) {
            return;
        }
        Counter counter = counters.get(selectionHash);
        if (counter == null) {
            if (counters.size() >= TRACKED) {
                overflow.increment();
                return;
            }
            counter = counters.computeIfAbsent(selectionHash, hash -> Counter.builder("kitbash.generations.byselection")
                    .tag("selection", hash)
                    .description("Generations, by canonical selection hash")
                    .register(meters));
        }
        counter.increment();
    }

    /**
     * The most-generated selections, from history rather than from this process's counters.
     *
     * <p>Empty without a database, which is not a failure: a deployment with no persistence has no
     * history to feed the nightly, and the enumerated matrix is the whole matrix there.
     */
    public List<GenerationRepository.PopularSelection> top(int limit) {
        GenerationRepository repository = history.getIfAvailable();
        return repository == null ? List.of() : repository.mostGenerated(limit);
    }
}
