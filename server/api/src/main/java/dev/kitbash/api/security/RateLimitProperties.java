package dev.kitbash.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How modest "modest" is (§13, §18).
 *
 * <p>These numbers are for one internal team, not a public service. They exist to stop a loop in
 * somebody's script from saturating the box, not to meter usage — §13 says modest, and a limit
 * tight enough to interrupt real work would make the product worse for the people it is for.
 *
 * @param generatePerMinute sustained rate for {@code /generate}
 * @param generateBurst how many may be spent at once before the sustained rate applies
 * @param sharePerMinute sustained rate for {@code /share}
 * @param shareBurst as above
 */
@ConfigurationProperties(prefix = "kitbash.limits")
public record RateLimitProperties(int generatePerMinute, int generateBurst, int sharePerMinute, int shareBurst) {

    public RateLimitProperties {
        generatePerMinute = positiveOr(generatePerMinute, 60);
        generateBurst = positiveOr(generateBurst, 20);
        sharePerMinute = positiveOr(sharePerMinute, 30);
        shareBurst = positiveOr(shareBurst, 10);
    }

    public Limit forBucket(RateLimiter.Bucket bucket) {
        return switch (bucket) {
            case GENERATE -> new Limit(generatePerMinute, generateBurst);
            case SHARE -> new Limit(sharePerMinute, shareBurst);
        };
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    /** One bucket's numbers, in the form the limiter does arithmetic with. */
    public record Limit(int perMinute, int burst) {

        public long nanosPerToken() {
            return Duration.ofMinutes(1).toNanos() / perMinute;
        }
    }
}
