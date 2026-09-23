package dev.kitbash.api.error;

import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.Stage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Why requests are rejected, counted by type (§14, §39).
 *
 * <p>§14 asks for validation failure reasons as a metric, and the reason is not observability for
 * its own sake: a code that suddenly dominates is a catalog problem wearing a user's clothes. A
 * spike in {@code CAPABILITY_UNSATISFIED} on one option means a recipe declared a {@code requires}
 * nothing provides; a spike in {@code INVALID_IDENTIFIER} on {@code packageName} means the rule and
 * the help text disagree. Neither shows up in an error <em>rate</em>, only in the breakdown.
 *
 * <p>Tagged by code and stage, and by nothing else. §10 keeps names and values out of anything
 * retained, and a tag is retained for as long as the series is — so the field an identifier failed
 * on is deliberately not a tag, however useful it would be. Cardinality is the other reason: a tag
 * whose values come from user input is a tag that can be made unbounded by a script.
 */
@Component
public class RejectionMetrics {

    private final MeterRegistry meters;

    public RejectionMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /** One rejection, by the code that named it and the stage it came out of. */
    public void record(GenerationError error) {
        count(error.code().name(), error.stage());
    }

    /**
     * A rejection that is not a generation failure.
     *
     * <p>A missing preset has no §6 stage, so it has no {@link ErrorCode} — forcing one on it would
     * put a lie in the envelope to satisfy a type. It is still counted, under its own name, because
     * "requests refused" is the question and the breakdown should add up.
     */
    public void recordUntyped(String reason, Stage stage) {
        count(reason, stage);
    }

    private void count(String code, Stage stage) {
        Counter.builder("kitbash.rejections")
                .tag("code", code)
                .tag("stage", stage == null ? "unknown" : stage.wireName())
                .description("Requests refused, by the §14 error code that refused them")
                .register(meters)
                .increment();
    }

    /** Every code, so a dashboard has a series before the first failure rather than after it. */
    public void preRegister() {
        for (ErrorCode code : ErrorCode.values()) {
            for (Stage stage : Stage.values()) {
                Counter.builder("kitbash.rejections")
                        .tag("code", code.name())
                        .tag("stage", stage.wireName())
                        .description("Requests refused, by the §14 error code that refused them")
                        .register(meters);
            }
        }
    }
}
