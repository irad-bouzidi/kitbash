package dev.kitbash.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The limiter, on a clock the test controls (§13).
 *
 * <p>A burst test that waited for a real minute would be a test nobody runs, so the clock is a
 * counter this file advances. Everything else is the real limiter, including the arithmetic.
 */
class RateLimiterTest {

    /** Three a minute, two at once: small enough to exhaust in a test, same shape as production. */
    private static final RateLimitProperties LIMITS = new RateLimitProperties(3, 2, 3, 2);

    private final AtomicLong nanos = new AtomicLong();
    private final RateLimiter limiter = RateLimiter.withClock(LIMITS, nanos::get);

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    @DisplayName("a burst is allowed, and then it is not")
    void burstsThenRefuses() {
        assertThatCode(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(thrown -> assertThat(((RateLimitExceededException) thrown).retryAfterSeconds())
                        .as("§14: the hint has to name when, not just that")
                        .isEqualTo(20));
    }

    /**
     * A token bucket rather than a fixed window, and this is the difference: a window would hand
     * back the whole allowance at once, letting a caller spend two minutes' budget across the
     * boundary in a second.
     */
    @Test
    @DisplayName("budget returns gradually, one token at a time, not all at once")
    void refillsContinuously() {
        limiter.require(RateLimiter.Bucket.GENERATE, "alice");
        limiter.require(RateLimiter.Bucket.GENERATE, "alice");
        assertThatThrownBy(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .isInstanceOf(RateLimitExceededException.class);

        advance(Duration.ofSeconds(20));
        assertThatCode(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .as("one token's worth of time has passed, so one request is allowed")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .as("and only one")
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("the bucket never fills past its burst, however long nobody calls")
    void doesNotAccumulateForever() {
        advance(Duration.ofHours(1));

        limiter.require(RateLimiter.Bucket.GENERATE, "alice");
        limiter.require(RateLimiter.Bucket.GENERATE, "alice");
        assertThatThrownBy(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    /**
     * §13 says key by user, then IP — in that order. Keying by IP first would put everyone behind
     * one office NAT into a single bucket, which is a limit on the company rather than on a caller.
     */
    @Test
    @DisplayName("one caller running out does not affect another")
    void bucketsArePerCaller() {
        limiter.require(RateLimiter.Bucket.GENERATE, "sub:alice");
        limiter.require(RateLimiter.Bucket.GENERATE, "sub:alice");
        assertThatThrownBy(() -> limiter.require(RateLimiter.Bucket.GENERATE, "sub:alice"))
                .isInstanceOf(RateLimitExceededException.class);

        assertThatCode(() -> limiter.require(RateLimiter.Bucket.GENERATE, "sub:bob"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("generating and sharing have separate budgets, because they cost differently")
    void bucketsAreSeparate() {
        limiter.require(RateLimiter.Bucket.GENERATE, "alice");
        limiter.require(RateLimiter.Bucket.GENERATE, "alice");
        assertThatThrownBy(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .isInstanceOf(RateLimitExceededException.class);

        assertThatCode(() -> limiter.require(RateLimiter.Bucket.SHARE, "alice")).doesNotThrowAnyException();
    }

    /**
     * Without this, a caller who keeps hammering a spent bucket digs it deeper with every refused
     * request, and a two-second burst turns into a lockout measured in minutes. The refusal costs
     * them nothing beyond the refusal.
     */
    @Test
    @DisplayName("being refused does not cost extra, so hammering does not deepen the hole")
    void refusalDoesNotCompound() {
        limiter.require(RateLimiter.Bucket.GENERATE, "alice");
        limiter.require(RateLimiter.Bucket.GENERATE, "alice");
        for (int attempt = 0; attempt < 50; attempt++) {
            assertThatThrownBy(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                    .isInstanceOf(RateLimitExceededException.class);
        }

        advance(Duration.ofSeconds(20));

        assertThatCode(() -> limiter.require(RateLimiter.Bucket.GENERATE, "alice"))
                .as("fifty refusals later, one token's worth of time still buys one request")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the defaults are modest rather than absent, per §18")
    void hasSaneDefaults() {
        RateLimitProperties defaults = new RateLimitProperties(0, 0, 0, 0);

        assertThat(defaults.generatePerMinute()).isEqualTo(60);
        assertThat(defaults.generateBurst()).isEqualTo(20);
        assertThat(defaults.sharePerMinute()).isEqualTo(30);
        assertThat(defaults.shareBurst()).isEqualTo(10);
    }
}
