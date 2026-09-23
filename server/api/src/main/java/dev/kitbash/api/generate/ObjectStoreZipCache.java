package dev.kitbash.api.generate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Determinism, cashed in as a cache (§10).
 *
 * <p>This is only safe because §4 made rendering deterministic: the same catalog and the same
 * canonical selection produce the same bytes, so a hit can be served <i>verbatim</i>. In a
 * generator whose output varied — a timestamp in a header, a map iterated in hash order — the same
 * cache would be a correctness bug that showed up as one person's project differing from another's.
 *
 * <p>The digest is part of the key, so a catalog change cannot be served stale. That is a property
 * of the key rather than a risk to manage, and there is a test for it.
 *
 * <p>Two layers, for two different costs. The object store is the cache; the in-memory map in front
 * of it remembers which keys are <i>there</i>, so a repeat hit does not pay a round trip to ask a
 * question it has already asked.
 */
@Component
@Primary
@Profile("objectstore")
@EnableConfigurationProperties(ObjectStoreProperties.class)
public class ObjectStoreZipCache implements ZipCache {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreZipCache.class);

    /**
     * How many keys the in-memory layer remembers.
     *
     * <p>Keys and lengths only — the bytes live in the object store. A thousand of them is a few
     * hundred kilobytes and covers the working set of a team, which is what §10 means by a bounded
     * map for key to object mapping.
     */
    private static final int REMEMBERED_KEYS = 1_000;

    private final S3Client s3;
    private final ObjectStoreProperties properties;
    private final CacheMetrics metrics;

    /** Key to the size of the object behind it; absence means "not asked yet", not "not there". */
    private final Map<String, Integer> known =
            Collections.synchronizedMap(new LinkedHashMap<>(REMEMBERED_KEYS + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > REMEMBERED_KEYS;
                }
            });

    public ObjectStoreZipCache(S3Client s3, ObjectStoreProperties properties, CacheMetrics metrics) {
        this.s3 = s3;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public Optional<byte[]> find(String key) {
        try {
            ResponseBytes<?> object = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            byte[] zip = object.asByteArray();
            known.put(key, zip.length);
            metrics.hit();
            return Optional.of(zip);
        } catch (NoSuchKeyException absent) {
            metrics.miss();
            return Optional.empty();
        } catch (S3Exception unavailable) {
            // A cache that is down is a slow generator, not a broken one. Rendering is the
            // fallback and it is always correct, so this is logged and shrugged off rather than
            // turned into a 500 for something the user never asked for.
            log.warn("The zip cache could not be read ({}); rendering instead", unavailable.getMessage());
            metrics.miss();
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, byte[] zip) {
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType("application/zip")
                            .build(),
                    RequestBody.fromBytes(zip));
            known.put(key, zip.length);
        } catch (S3Exception unwritable) {
            // Same reasoning: failing to cache is not failing to generate, and the user already
            // has their zip by the time this runs.
            log.warn(
                    "The zip cache could not be written ({}); the generation still succeeded", unwritable.getMessage());
        }
    }

    @Override
    public boolean stores() {
        return true;
    }

    /** Whether this key has been seen, without asking the object store again. */
    boolean remembers(String key) {
        return known.containsKey(key);
    }
}
