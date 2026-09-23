package dev.kitbash.api.verify;

import dev.kitbash.verify.Repository;
import dev.kitbash.verify.VerificationResults;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Badges, which need no database.
 *
 * <p>Separate from {@link VerificationConfiguration} because the two are gated differently and the
 * difference is meaningful. Running a verification is a row, so it needs persistence; <em>reading</em>
 * what the nightly already found is a file, so a deployment with no database still shows a user
 * which combinations are known to be red. That is the half of §9 that matters most while choosing,
 * and withholding it from a database-less deployment would be gating information on infrastructure
 * it does not depend on.
 */
@Configuration
public class VerificationBadgeConfiguration {

    /**
     * Where the matrix leaves its results (§12, kitbash-38).
     *
     * <p>A path rather than a table, because §12 keeps the runner independent of the API: it has no
     * connection and writes a document, and this reads it. Configurable because in a deployment the
     * file arrives from somewhere other than the repository it was written in.
     */
    @Bean
    public VerificationBadges verificationBadges(@Value("${kitbash.verify.results:}") String configured) {
        return new VerificationBadges(resultsFile(configured));
    }

    /**
     * The matrix's logs, which sit beside its results.
     *
     * <p>Derived from the results path rather than configured separately: the two are written by
     * one run into one directory, and two settings that have to agree are two settings that
     * eventually will not.
     */
    @Bean
    public MatrixLogs matrixLogs(@Value("${kitbash.verify.results:}") String configured) {
        return new MatrixLogs(resultsFile(configured).getParent().resolve("logs"));
    }

    private static Path resultsFile(String configured) {
        return configured.isBlank()
                ? Repository.locate().output().resolve(VerificationResults.FILE_NAME)
                : Path.of(configured).toAbsolutePath();
    }
}
