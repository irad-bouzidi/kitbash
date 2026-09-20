package dev.kitbash.api.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Modest limits on the two endpoints that cost something (§13).
 *
 * <p>A token bucket rather than a fixed window, because a fixed window lets a caller spend a whole
 * minute's budget in its last second and the next minute's in its first — twice the intended rate
 * at the moment load is highest. A bucket refills continuously, so the limit means what it says.
 *
 * <p><b>In memory, and deliberately.</b> §18 settles the audience as one internal team, so a single
 * instance holds the whole state and there is nothing to share. If this service is ever run as more
 * than one instance the limits become per instance, which is a real behaviour change and is called
 * out here rather than assumed away with a Redis that does not exist yet.
 *
 * <p>The part worth reading twice is where this is <i>called</i> from — see
 * {@link #require(Bucket, String)}.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

    /** What is being limited. Each has its own budget because the costs differ by orders. */
    public enum Bucket {
        /** Renders and packages a project: tens of milliseconds of CPU and a few hundred KB. */
        GENERATE,
        /** Writes a row and returns a token. Cheap, but an unbounded token factory is a spam tool. */
        SHARE
    }

    private final RateLimitProperties limits;
    private final Map<String, AtomicReference<State>> buckets = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    // Explicit, because the private constructor below means Spring will not pick one for itself.
    @org.springframework.beans.factory.annotation.Autowired
    public RateLimiter(RateLimitProperties limits) {
        this(limits, System::nanoTime);
    }

    private RateLimiter(RateLimitProperties limits, java.util.function.LongSupplier nanoClock) {
        this.limits = limits;
        this.clock = nanoClock;
    }

    /**
     * The same limiter on a clock a test controls.
     *
     * <p>A factory rather than a second constructor, so the container has exactly one to choose
     * from — and so that a burst test can watch a bucket refill without spending a real minute
     * doing it.
     */
    static RateLimiter withClock(RateLimitProperties limits, java.util.function.LongSupplier nanoClock) {
        return new RateLimiter(limits, nanoClock);
    }

    /**
     * Spends one unit of budget, or refuses.
     *
     * <p><b>This is called from inside the request, after a cache lookup would have answered — not
     * from a servlet filter.</b> §13 requires a cache hit to cost nothing, and a filter runs before
     * the handler has had a chance to find one, so a filter would charge for the exact usage the
     * product most wants to encourage: regenerating the house stack over and over, which is served
     * from bytes that already exist. {@code kitbash-27} adds the cache above this call, and the
     * shape of the flow is what keeps that free.
     *
     * @param key the caller: the token subject where there is one, the remote address otherwise
     * @throws RateLimitExceededException with the wait, when the bucket is empty
     */
    public void require(Bucket bucket, String key) {
        Duration retryAfter = tryConsume(bucket, key);
        if (retryAfter != null) {
            throw new RateLimitExceededException(bucket, retryAfter);
        }
    }

    /** The wait before the next unit is available, or null when one was just spent. */
    Duration tryConsume(Bucket bucket, String key) {
        RateLimitProperties.Limit limit = limits.forBucket(bucket);
        AtomicReference<State> state =
                buckets.computeIfAbsent(bucket + "\u0000" + key, ignored -> new AtomicReference<>(State.full(limit)));

        long now = clock.getAsLong();
        State updated = state.updateAndGet(current -> current.refill(limit, now).spend());
        if (updated.tokens() >= 0) {
            return null;
        }

        // Undo the overspend so a caller who keeps hammering does not dig the bucket deeper and
        // deeper, which would turn a brief burst into a long lockout.
        state.updateAndGet(State::giveBack);
        return Duration.ofNanos(limit.nanosPerToken());
    }

    /**
     * Tokens, and when they were last counted.
     *
     * <p>A record in an {@link AtomicReference} rather than a synchronised block: the whole update
     * is a pure function of the previous state, so a compare-and-set loop is both correct under
     * contention and free when there is none.
     */
    private record State(double tokens, long lastRefillNanos) {

        static State full(RateLimitProperties.Limit limit) {
            return new State(limit.burst(), Long.MIN_VALUE);
        }

        State refill(RateLimitProperties.Limit limit, long now) {
            if (lastRefillNanos == Long.MIN_VALUE) {
                return new State(limit.burst(), now);
            }
            double earned = (double) (now - lastRefillNanos) / limit.nanosPerToken();
            return new State(Math.min(limit.burst(), tokens + earned), now);
        }

        State spend() {
            return new State(tokens - 1, lastRefillNanos);
        }

        State giveBack() {
            return new State(tokens + 1, lastRefillNanos);
        }
    }
}
