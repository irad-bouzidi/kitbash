package dev.kitbash.api.verify;

import dev.kitbash.api.generate.ObjectStoreProperties;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Run logs in the same bucket as the zips, under the same thirty-day lifecycle (§10).
 *
 * <p>Same bucket rather than a second one, because the retention rule is the bucket's and §10 gives
 * every artifact the same thirty days. A second bucket would be a second lifecycle to keep in step
 * with this one, and the first time they disagreed the symptom would be a run whose row still
 * names a log that expired a fortnight ago.
 *
 * <p>The prefix is what separates them. A key beginning {@code verify/} is a log; anything else is
 * a zip.
 */
@Component
@Primary
@Profile("objectstore")
public class ObjectStoreVerificationLogs implements VerificationLogs {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreVerificationLogs.class);

    private final S3Client s3;
    private final ObjectStoreProperties properties;

    public ObjectStoreVerificationLogs(S3Client s3, ObjectStoreProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public String put(String runId, String text) {
        String key = "verify/" + runId + ".log";
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType("text/plain; charset=utf-8")
                            .build(),
                    RequestBody.fromString(text, StandardCharsets.UTF_8));
            return key;
        } catch (S3Exception unwritable) {
            // The run itself is the answer, and it is already known. A log that could not be
            // stored costs the user their detail, not their result, so the row records no key
            // rather than a key pointing at nothing.
            log.warn("The log for run {} could not be stored ({})", runId, unwritable.getMessage());
            return null;
        }
    }

    @Override
    public Optional<String> find(String key) {
        try {
            ResponseBytes<?> object = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return Optional.of(object.asUtf8String());
        } catch (NoSuchKeyException expired) {
            // Thirty days passed, or it was never written. Either way the run's verdict stands and
            // only its detail is gone, which is what the caller is told.
            return Optional.empty();
        } catch (S3Exception unavailable) {
            log.warn("The log at {} could not be read ({})", key, unavailable.getMessage());
            return Optional.empty();
        }
    }
}
