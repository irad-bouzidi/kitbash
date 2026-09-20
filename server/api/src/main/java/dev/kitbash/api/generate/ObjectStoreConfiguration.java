package dev.kitbash.api.generate;

import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The object store, and the half of retention it owns (§5, §10).
 *
 * <p>§10 splits the thirty days by who is best placed to enforce them: cached zips expire by a
 * lifecycle rule, rows and logs by a nightly sweep. The rule is set here, at startup, rather than
 * left to whoever provisioned the bucket — a retention policy that lives in somebody's console is
 * one nobody can review, and one that silently was not applied looks exactly like one that was.
 */
@Configuration
@Profile("objectstore")
@EnableConfigurationProperties(ObjectStoreProperties.class)
public class ObjectStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreConfiguration.class);

    @Bean
    public S3Client s3Client(ObjectStoreProperties properties) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(properties.region()));

        if (properties.hasOwnEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()))
                    // A bucket is a hostname on AWS and a path everywhere else. MinIO is
                    // everywhere else.
                    .forcePathStyle(true);
        }
        if (properties.accessKey() != null && !properties.accessKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        } else {
            // A deployment on AWS gets its credentials the way everything else there does.
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }

        S3Client s3 = builder.build();
        ensureBucket(s3, properties);
        return s3;
    }

    /**
     * Makes the bucket if it is missing, and states the retention either way.
     *
     * <p>Failures here are logged rather than thrown. A generator whose cache is unreachable is a
     * slower generator; one that refuses to start is an outage — and §10 is explicit that the
     * cache is an optimisation resting on determinism rather than a source of truth.
     */
    private void ensureBucket(S3Client s3, ObjectStoreProperties properties) {
        try {
            s3.headBucket(
                    HeadBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (NoSuchBucketException missing) {
            log.info("Creating the artifact bucket {}", properties.bucket());
            s3.createBucket(
                    CreateBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (S3Exception unreachable) {
            log.warn(
                    "The object store is not reachable ({}); generations will render rather than be cached",
                    unreachable.getMessage());
            return;
        }

        applyRetention(s3, properties);
    }

    private void applyRetention(S3Client s3, ObjectStoreProperties properties) {
        try {
            s3.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                    .bucket(properties.bucket())
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                            .rules(List.of(LifecycleRule.builder()
                                    .id("kitbash-30-days")
                                    .status(ExpirationStatus.ENABLED)
                                    // Every object: the key is a content hash, so there is no
                                    // prefix that means anything and nothing here is exempt.
                                    .filter(LifecycleRuleFilter.builder()
                                            .prefix("")
                                            .build())
                                    .expiration(LifecycleExpiration.builder()
                                            .days(properties.retentionDays())
                                            .build())
                                    .build()))
                            .build())
                    .build());
            log.info("Artifacts in {} expire after {} days", properties.bucket(), properties.retentionDays());
        } catch (S3Exception refused) {
            // Some S3-compatible stores do not implement lifecycle rules. Saying so is better
            // than pretending the policy is in force, because the sweep does not cover this half.
            log.warn(
                    "The object store would not accept a {}-day lifecycle rule ({}). Cached zips will "
                            + "not expire on their own — set the policy on the bucket by hand.",
                    properties.retentionDays(),
                    refused.getMessage());
        }
    }
}
