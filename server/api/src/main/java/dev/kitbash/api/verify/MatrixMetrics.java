package dev.kitbash.api.verify;

import dev.kitbash.core.recipe.Catalog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The matrix's pass rate, published from where something is actually scraped (§14, §41).
 *
 * <h2>Why this is not the runner's metric</h2>
 *
 * <p>§14 lists <i>matrix pass rate per cell</i> among the metrics, and the matrix is the obvious
 * place to emit it — except that the matrix is a batch job in CI. It starts, runs for twenty
 * minutes and exits; nothing scrapes it, and pushing from it would mean a push gateway that does
 * not exist and that §41 puts out of scope along with the rest of the deployment's plumbing.
 *
 * <p>The API already reads the published results for {@code kitbash-38}'s badges, and the API
 * <em>is</em> scraped. So the gauge is derived here, from the same document the badges are. The
 * useful consequence is that a badge and a graph cannot disagree about a cell: there is one
 * publication and two readers of it.
 *
 * <h2>Not per cell</h2>
 *
 * <p>Ninety-eight cells would be ninety-eight series, each a value that changes once a night, and a
 * cell id is as much user-facing vocabulary as a recipe id — but the per-cell answer already exists
 * as data, on the status page and in {@code verification.json}, where a human looks when something
 * is red. What a graph is for is the trend: the rate, and how many cells it was computed from.
 */
@Component
public class MatrixMetrics {

    private final VerificationBadges badges;
    private final Catalog catalog;

    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger total = new AtomicInteger();

    public MatrixMetrics(VerificationBadges badges, Catalog catalog, MeterRegistry meters) {
        this.badges = badges;
        this.catalog = catalog;

        Gauge.builder("kitbash.matrix.cells", total, AtomicInteger::get)
                .description("Cells in the most recent published matrix run for this catalog")
                .register(meters);
        Gauge.builder("kitbash.matrix.passed", passed, AtomicInteger::get)
                .description("Cells that passed in that run; the pass rate is this over the count")
                .register(meters);
    }

    /**
     * Re-read on a timer, because the file is replaced by a job on the other side of a deployment.
     *
     * <p>Five minutes: a nightly lands once a day, and a gauge up to five minutes stale about it
     * is exactly as useful as one that is current. Ten seconds before the first read, so a freshly
     * started process publishes a number rather than a zero that looks like a red matrix.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT10S")
    public void refresh() {
        VerificationBadges.Badges current = badges.forCatalog(catalog.digest());

        // Choices rather than pairs: one entry per chosen value is closer to "how much of the
        // matrix is green" than the pairwise expansion, which counts a red cell once per pairing
        // it contains and would make one failure look like a dozen.
        int green = 0;
        int all = 0;
        for (VerificationBadges.Badge badge : current.choices()) {
            green += badge.passed();
            all += badge.passed() + badge.failed();
        }
        passed.set(green);
        total.set(all);
    }
}
