package dev.kitbash.api.generate;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The seam the zip cache plugs into (§10, kitbash-27).
 *
 * <p>The cache itself — object storage, a bounded key map, a 30-day lifecycle — is
 * {@code kitbash-27}. This interface exists in {@code kitbash-22} because of a requirement that
 * belongs to the limiter rather than to the cache: §13 says <b>a cache hit consumes no budget</b>,
 * and where the lookup sits relative to the limiter is what makes that true or false.
 *
 * <p>Declaring the seam now means the ordering is tested now, by a test that hands the controller
 * a cache that hits. Without it the requirement would be a comment, and a comment is not something
 * {@code kitbash-27} can accidentally violate and find out about.
 *
 * <p>Keys are {@code sha256(catalogDigest + canonicalSelection)} — §10's key, computed from the
 * parsed selection, which is why parsing happens before the lookup.
 */
public interface ZipCache {

    /** The bytes for this key, if some earlier identical request already produced them. */
    Optional<byte[]> find(String key);

    /** Offers the bytes for reuse. A cache that does not want them may ignore this. */
    void put(String key, byte[] zip);

    /**
     * Whether anything will actually be stored.
     *
     * <p>Filling a cache means holding the whole zip before writing it, and there is no reason to
     * pay for that copy when nothing is listening. The default implementation answers no, so today
     * the response streams as it always did.
     */
    default boolean stores() {
        return true;
    }

    /**
     * The default: no cache at all.
     *
     * <p>Every generation is rendered, which is correct and costs tens of milliseconds. The
     * determinism §4 guarantees is what will make caching safe, and {@code kitbash-27} is where
     * that is spent.
     */
    @Component
    class None implements ZipCache {

        @Override
        public Optional<byte[]> find(String key) {
            return Optional.empty();
        }

        @Override
        public void put(String key, byte[] zip) {
            // Nowhere to put it yet.
        }

        @Override
        public boolean stores() {
            return false;
        }
    }
}
