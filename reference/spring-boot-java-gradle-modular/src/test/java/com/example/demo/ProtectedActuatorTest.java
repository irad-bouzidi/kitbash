package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * The interaction between two recipes, tested because neither recipe can test it alone.
 *
 * <p>Actuator's metrics endpoints describe the deployment: heap, thread counts, datasource pool
 * size, every URL the application serves and how often each is called. Left open they are a
 * reconnaissance endpoint, and "it is only metrics" is how that happens. This project selected
 * both observability and auth, so the rule is that everything except the health probes needs a
 * token — and the failure mode being guarded against is a future change to either recipe's
 * endpoint list quietly undoing it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
class ProtectedActuatorTest extends PostgresTestBase {

    @LocalServerPort
    private int port;

    /** Deliberately not the injected one, which carries a token. */
    private final TestRestTemplate anonymous = new TestRestTemplate();

    @Test
    @DisplayName("the metrics endpoints need a token, because they describe the deployment")
    void protectsMetrics() {
        assertThat(status("/actuator/prometheus")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status("/actuator/metrics")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the health probes stay open, because the platform calling them has no token")
    void leavesProbesOpen() {
        assertThat(status("/actuator/health")).isEqualTo(HttpStatus.OK);
        assertThat(status("/actuator/health/readiness")).isEqualTo(HttpStatus.OK);
    }

    private HttpStatus status(String path) {
        return HttpStatus.valueOf(anonymous
                .getForEntity("http://localhost:" + port + path, String.class)
                .getStatusCode()
                .value());
    }
}
