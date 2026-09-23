package dev.kitbash.api.generate;

import dev.kitbash.core.lock.Lock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * What §14 asks to be able to see about generation, and nothing §10 forbids.
 *
 * <h2>The cardinality rule, and what it cost</h2>
 *
 * <p>§41 states it plainly: <i>recipe usage tagged by recipe id is fine; anything tagged by
 * selection hash is not a metric, it is a log line.</i> A recipe id comes from a closed set the
 * catalog defines — a few dozen series, and a new one is a deliberate act. A selection hash comes
 * from user input, so the series count is whatever people generate, and an unbounded set of time
 * series is how a monitoring bill becomes a postmortem.
 *
 * <p>{@code kitbash-27} had a counter tagged by selection hash, bounded at two hundred series as
 * its mitigation. §41's rule does not admit a bounded exception, and the number it was there to
 * produce — which stacks are popular — is answered by {@code PopularSelections.top}, from the
 * database, where a selection hash is a column rather than a dimension. So the tag is gone and the
 * answer is not.
 *
 * <p>§10 governs the rest: <b>no project or package names</b>, here or anywhere near a log line. A
 * metric is shipped somewhere else and kept on somebody else's retention schedule, which is the
 * same reason logs cannot carry them — and a tag is worse than a message, because it persists as a
 * series long after the request is forgotten.
 */
@Component
public class GenerationMetrics {

    private final MeterRegistry meters;

    /** Every way a generation can end, so the series exist before the first request. */
    private static final java.util.List<String> OUTCOMES = java.util.List.of("succeeded", "failed", "cached");

    public GenerationMetrics(MeterRegistry meters) {
        this.meters = meters;
        // A meter that appears on first use makes a fresh deployment's dashboard read "No data"
        // everywhere, which looks like a broken scrape rather than a quiet morning.
        OUTCOMES.forEach(this::timer);
    }

    /**
     * How long a generation took and how it ended, in one meter.
     *
     * <p>A timer with an {@code outcome} tag rather than a timer and a counter: the two questions
     * §14 asks — how long, and how did it end — are asked together, and a failure rate computed
     * from two meters that could be updated on different paths is a rate that can be wrong.
     */
    public void generated(Duration took, String outcome) {
        timer(outcome).record(took);
    }

    private Timer timer(String outcome) {
        return Timer.builder("kitbash.generations")
                .tag("outcome", outcome)
                .description("Generations, by how they ended")
                .register(meters);
    }

    /**
     * Which recipes are actually used.
     *
     * <p>From the lock rather than from the selection: the lock is the resolved set, so a recipe
     * the resolver added counts as used — which it was. Counting the selection would say a database
     * is unpopular because nobody picks one on purpose.
     */
    public void used(Lock lock) {
        lock.recipeVersions().keySet().forEach(recipe -> Counter.builder("kitbash.recipes.used")
                .tag("recipe", recipe.value())
                .description("Generations including this recipe, resolved rather than selected")
                .register(meters)
                .increment());
    }
}
