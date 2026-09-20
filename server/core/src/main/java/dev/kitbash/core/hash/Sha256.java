package dev.kitbash.core.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The one hash function in the system, so every digest in the plan means the same thing.
 *
 * <p>Three load-bearing values are computed with it — the catalog digest (§7), the selection hash
 * that keys the zip cache (§10) and the verification dedupe key (§12). They are compared across
 * machines and across releases, so the encoding is pinned here rather than left to each caller:
 * UTF-8 in, lowercase hex out, no truncation.
 */
public final class Sha256 {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Sha256() {}

    public static String ofUtf8(String value) {
        return of(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String of(byte[] value) {
        return hex(newDigest().digest(value));
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256; a JRE without it cannot run this application at all.
            throw new IllegalStateException("SHA-256 is not available on this JVM", impossible);
        }
    }

    public static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xff;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0f];
        }
        return new String(out);
    }
}
