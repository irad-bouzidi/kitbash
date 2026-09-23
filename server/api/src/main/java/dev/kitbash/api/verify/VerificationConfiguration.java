package dev.kitbash.api.verify;

import dev.kitbash.api.store.VerificationRunRepository;
import dev.kitbash.core.recipe.Catalog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires {@code kitbash-37}'s three pieces: the pool, the runner and the service over them.
 *
 * <p>The pool is a bean rather than a static because its limits are configuration (§12) and because
 * a test that wants a pool of one should get one without a system property. {@code close()} on
 * shutdown is inherited from the bean lifecycle, which is what stops a redeploy leaving worker
 * threads behind.
 */
@Configuration
@Profile("persistence")
@EnableConfigurationProperties(VerificationProperties.class)
public class VerificationConfiguration {

    /**
     * The clock, injectable so a test can move time without sleeping through a deadline.
     *
     * <p>{@code @ConditionalOnMissingBean} rather than unconditional: a test that needs a fixed
     * clock replaces it, and one that does not gets the system's.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The pool, with its depth and occupancy published as gauges.
     *
     * <p>§37 asks for the queue depth in metrics as well as in the 429 body, and the two answer
     * different questions. The body tells one caller whether to wait; the gauge tells whoever runs
     * this whether four workers is the right number — a depth that is usually zero and occasionally
     * thirty-two is a different problem from one that never drops below ten.
     */
    @Bean(destroyMethod = "close")
    public VerificationWorkers verificationWorkers(VerificationProperties properties, MeterRegistry meters) {
        VerificationWorkers workers = new VerificationWorkers(properties);
        Gauge.builder("kitbash.verify.queue.depth", workers, VerificationWorkers::queueDepth)
                .description("Verification runs waiting for a worker")
                .register(meters);
        Gauge.builder("kitbash.verify.running", workers, VerificationWorkers::running)
                .description("Verification runs in containers right now")
                .register(meters);
        return workers;
    }

    @Bean
    public VerificationService verificationService(
            VerificationRunRepository runs,
            VerificationWorkers workers,
            VerificationRunner runner,
            VerificationLogs logs,
            Catalog catalog,
            Clock clock,
            VerificationMetrics outcomes) {
        return new VerificationService(runs, workers, runner, logs, catalog::digest, clock, outcomes);
    }
}
