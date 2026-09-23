package dev.kitbash.api.generate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Hit and miss, counted wherever the cache seam is — not only where a cache is (§14, §41).
 *
 * <p>These lived on {@link ObjectStoreZipCache}, which exists only under the {@code objectstore}
 * profile. §41 asks for cache hit rate on the dashboard, and a metric that disappears with an
 * implementation is a panel that reads "No data" on exactly the deployment somebody is trying to
 * understand — indistinguishable from a cache that is simply quiet.
 *
 * <p>So the counters belong to the seam. {@link ZipCache.None} misses every time, which is true
 * rather than flattering: a deployment with no object store has a hit rate of zero, and a graph
 * showing traffic at zero percent is the correct reading of "nothing is cached here".
 *
 * <p>§27's reason for wanting this at all is unchanged, and it is not about cost. The hit rate is
 * the number that says whether <b>determinism is still holding</b> in production: the same catalog
 * and the same canonical selection must produce the same bytes, and a rate that collapses means
 * the output stopped being reproducible. That shows up here before a user reports two different
 * zips for one selection.
 */
@Component
public class CacheMetrics {

    private final Counter hits;
    private final Counter misses;

    public CacheMetrics(MeterRegistry meters) {
        this.hits = Counter.builder("kitbash.cache.requests")
                .tag("result", "hit")
                .description("Generations served from the zip cache")
                .register(meters);
        this.misses = Counter.builder("kitbash.cache.requests")
                .tag("result", "miss")
                .description("Generations that had to be rendered")
                .register(meters);
    }

    public void hit() {
        hits.increment();
    }

    public void miss() {
        misses.increment();
    }
}
