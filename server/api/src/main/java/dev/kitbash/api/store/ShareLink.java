package dev.kitbash.api.store;

import java.time.Instant;

/**
 * A selection somebody sent to somebody else (§10).
 *
 * <p>No owner column. The token is the capability, and a share link that required an account
 * would not be a share link.
 */
public record ShareLink(String token, String selection, Instant createdAt, Instant expiresAt) {}
