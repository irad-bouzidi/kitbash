package dev.kitbash.api.share;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The short token a share link carries (§8, §13).
 *
 * <p>Short because the whole point of a token is that the URL was too long to paste; there is no
 * value in trading one unwieldy string for another. Eleven characters of base32 is about 55 bits —
 * enough that guessing one is not a strategy, short enough to read over a desk.
 *
 * <p>Unpredictable, not merely unique. The token <i>is</i> the capability: anybody holding it can
 * read the selection, so a sequential id or a timestamp would let somebody walk the space. Crockford's
 * alphabet drops the characters people mistype into each other, because these get read aloud.
 */
final class ShareToken {

    /** Crockford base32: no I, L, O or U — the ones that turn into 1, 1, 0 and each other. */
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    private static final int LENGTH = 11;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareToken() {}

    static String next() {
        StringBuilder token = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            token.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return token.toString();
    }

    /**
     * Whether a string could be one of ours.
     *
     * <p>Checked before the database is asked, so a lookup by a hundred-kilobyte path parameter is
     * a rejection rather than a query.
     */
    static boolean looksLikeAToken(String token) {
        if (token == null || token.length() != LENGTH) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            if (new String(ALPHABET).indexOf(lower.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
