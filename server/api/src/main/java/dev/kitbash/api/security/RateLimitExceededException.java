package dev.kitbash.api.security;

import java.time.Duration;

/**
 * The budget is spent.
 *
 * <p>Carries the wait rather than only the fact, because §14 requires the hint to name the next
 * action and "try again later" is not one. The handler turns it into a {@code Retry-After} header
 * and a sentence with a number in it.
 */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient RateLimiter.Bucket bucket;
    private final transient Duration retryAfter;

    public RateLimitExceededException(RateLimiter.Bucket bucket, Duration retryAfter) {
        super("Rate limit exceeded for " + bucket);
        this.bucket = bucket;
        this.retryAfter = retryAfter;
    }

    public RateLimiter.Bucket bucket() {
        return bucket;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** At least a second: a Retry-After of zero invites an immediate retry that will also fail. */
    public long retryAfterSeconds() {
        return Math.max(1, retryAfter.toSeconds());
    }
}
