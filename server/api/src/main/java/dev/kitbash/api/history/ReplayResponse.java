package dev.kitbash.api.history;

import java.util.List;

/**
 * What a replay did, said out loud (§24).
 *
 * <p>The mode is echoed rather than assumed. §24 requires the response to state which one ran and
 * what changed if anything did, and the reason is that the two answer different questions: a
 * client that asked for {@code exact} and silently received today's versions would have a
 * reproduction that reproduces nothing.
 *
 * @param changes one line per recipe whose version moved, ready to read or paste
 */
public record ReplayResponse(
        String mode,
        String originalCatalogDigest,
        String currentCatalogDigest,
        boolean catalogMoved,
        String summary,
        List<LockDiff.VersionChange> changes) {}
