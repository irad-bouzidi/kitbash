package dev.kitbash.api.generate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the cached zips live (§5, §10).
 *
 * <p>S3-compatible rather than AWS-specific: MinIO is what runs locally and in compose, and the
 * only thing this code knows about the difference is that an endpoint can be overridden and that
 * path-style addressing is needed when it is — a bucket is a hostname on AWS and a path on MinIO.
 *
 * @param endpoint an S3-compatible endpoint, or blank for AWS itself
 * @param bucket where the objects go; created at startup when it does not exist
 * @param retentionDays §10's uniform thirty, applied as a lifecycle rule rather than by a job
 */
@ConfigurationProperties(prefix = "kitbash.objectstore")
public record ObjectStoreProperties(
        String endpoint, String bucket, String accessKey, String secretKey, String region, int retentionDays) {

    public ObjectStoreProperties {
        bucket = bucket == null || bucket.isBlank() ? "kitbash-artifacts" : bucket;
        region = region == null || region.isBlank() ? "us-east-1" : region;
        retentionDays = retentionDays > 0 ? retentionDays : 30;
    }

    public boolean hasOwnEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
