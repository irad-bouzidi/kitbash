package dev.kitbash.api.verify;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * How verification runs end (§14, §41).
 *
 * <p>{@code kitbash-37} published the queue depth and how many runs are in containers, which say
 * whether the pool is the right size. They say nothing about whether the runs are working: a queue
 * that is never deep and a run that always fails look identical from there.
 *
 * <p>Three outcomes rather than two. "The generated project did not build" is a user's problem and
 * the answer they asked for; "the runner could not start" is an operator's, and folding them into
 * one counter hides whichever is rarer — which is always the one worth knowing about.
 */
@Component
public class VerificationMetrics {

    private final MeterRegistry meters;

    /** The outcomes a run can have, pre-registered so the series exist before the first run. */
    private static final java.util.List<String> OUTCOMES = java.util.List.of("passed", "failed", "unstartable");

    public VerificationMetrics(MeterRegistry meters) {
        this.meters = meters;
        // Registered at startup rather than on first use. A counter that does not exist until its
        // first event makes a dashboard read "No data", which is indistinguishable from "not
        // deployed" — and the two call for opposite reactions at three in the morning.
        OUTCOMES.forEach(this::counter);
    }

    public void recorded(String outcome) {
        counter(outcome).increment();
    }

    private Counter counter(String outcome) {
        return Counter.builder("kitbash.verify.runs")
                .tag("outcome", outcome)
                .description("Verification runs, by how they ended")
                .register(meters);
    }
}
