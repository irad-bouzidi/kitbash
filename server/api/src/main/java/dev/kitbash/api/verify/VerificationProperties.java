package dev.kitbash.api.verify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §12's controls, as numbers somebody can change without a deploy.
 *
 * <p>They are concurrency limits rather than quotas, which §18 settles: verification is open to
 * every authenticated user, and what makes that affordable is that the work is deduplicated and
 * the machine is bounded — not that anyone is rationed. A quota would punish the person who asked
 * first for a question the whole team benefits from.
 *
 * @param workers how many runs may be in containers at once (§12's four)
 * @param perUser how many a single caller may have in flight — one, so a scripted loop cannot take
 *     the pool from everybody else
 * @param queueDepth how many may be waiting before the next caller is refused, with the depth, so
 *     they can decide rather than be silently queued for an hour
 * @param timeoutMinutes the hard deadline on a run, after which it is killed and reported failed
 */
@ConfigurationProperties(prefix = "kitbash.verify")
public record VerificationProperties(int workers, int perUser, int queueDepth, int timeoutMinutes) {

    public VerificationProperties {
        workers = workers > 0 ? workers : 4;
        perUser = perUser > 0 ? perUser : 1;
        queueDepth = queueDepth > 0 ? queueDepth : 32;
        timeoutMinutes = timeoutMinutes > 0 ? timeoutMinutes : 15;
    }
}
