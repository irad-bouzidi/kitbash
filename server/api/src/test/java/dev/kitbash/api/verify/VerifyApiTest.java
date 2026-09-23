package dev.kitbash.api.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The wire contract of §8's one asynchronous endpoint.
 *
 * <p>The status code is the whole design here, so it is what this asserts. <b>202</b> means a
 * container is starting; <b>200</b> means the question was already answered and nothing was spent.
 * A client that could not tell those apart would either poll a finished run for ever or report a
 * cached pass as "starting", and both are the kind of bug an integration test catches and a unit
 * test about the service cannot.
 *
 * <p>The runner is replaced, because what is under test is the endpoint rather than Docker.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "test"})
@AutoConfigureMockMvc
class VerifyApiTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** A runner that answers immediately and truthfully, so the endpoint is what is being tested. */
    @TestConfiguration
    static class StubRunner {

        static final CountDownLatch RAN = new CountDownLatch(1);

        @Bean
        @Primary
        VerificationRunner stubVerificationRunner() {
            return (runId, envelope, deadline) -> {
                RAN.countDown();
                return new VerificationRunner.Outcome(true, "green, in a test", null);
            };
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String selection(String projectName) {
        return """
               {"projectName":"%s","options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts",
                "database":"db-postgres-flyway"},
                "variables":{"groupId":"com.acme","packageName":"com.acme.billing","javaVersion":"21",
                "entityName":"Invoice","entityTable":"invoices","envPrefix":"ACME"}}"""
                .formatted(projectName);
    }

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor caller(String subject) {
        return jwt().jwt(token -> token.subject(subject));
    }

    @Test
    @DisplayName("the first ask is 202 with a job id; the second is 200 with the answer")
    void acceptedThenAnswered() throws Exception {
        String body = selection("first-ask");

        MockHttpServletResponse accepted = mvc.perform(post("/api/v1/verify")
                        .with(caller(UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();

        assertThat(accepted.getStatus())
                .as("a container is starting; body was %s", accepted.getContentAsString())
                .isEqualTo(202);
        JsonNode started = JSON.readTree(accepted.getContentAsString());
        assertThat(started.path("id").asText()).isNotBlank();
        assertThat(accepted.getHeader("Location"))
                .isEqualTo("/api/v1/verify/" + started.path("id").asText());

        assertThat(StubRunner.RAN.await(30, TimeUnit.SECONDS)).isTrue();
        // The row is written after the runner returns; the poll below is the client's own loop.
        JsonNode finished = poll(started.path("id").asText());
        assertThat(finished.path("status").asText()).isEqualTo("passed");
        assertThat(finished.path("log").asText()).isEqualTo("green, in a test");

        MockHttpServletResponse second = mvc.perform(post("/api/v1/verify")
                        .with(caller(UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();

        assertThat(second.getStatus())
                .as("somebody else's answer, without a container — §12's whole affordability claim")
                .isEqualTo(200);
        JsonNode answered = JSON.readTree(second.getContentAsString());
        assertThat(answered.path("id").asText()).isEqualTo(started.path("id").asText());
        assertThat(answered.path("status").asText()).isEqualTo("passed");
        assertThat(answered.path("log").asText())
                .as("an answer that exists comes back complete, so the second caller is finished "
                        + "rather than pointed at another request")
                .isEqualTo("green, in a test");
    }

    @Test
    @DisplayName("a selection that cannot even resolve is a 400, not minutes of a container")
    void refusesAnImpossibleSelectionBeforeSpendingAnything() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/verify")
                        .with(caller(UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"nothing\",\"options\":{},\"variables\":{}}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("error");
    }

    @Test
    @DisplayName("an unknown run id is a 404 problem document, not an empty 200")
    void unknownRunIsNotFound() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/verify/" + UUID.randomUUID())
                        .with(caller(UUID.randomUUID().toString())))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(JSON.readTree(response.getContentAsString()).path("error").asText())
                .isEqualTo("VERIFY_NOT_FOUND");
    }

    @Test
    @DisplayName("verification is closed to anonymous callers, like everything else (§13)")
    void closedWithoutAToken() throws Exception {
        assertThat(mvc.perform(post("/api/v1/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(selection("anonymous")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    /** The client's own loop, bounded: a run that never finishes is a failure of this test too. */
    private JsonNode poll(String id) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            JsonNode body = JSON.readTree(mvc.perform(get("/api/v1/verify/" + id)
                            .with(caller(UUID.randomUUID().toString())))
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            if (!body.path("status").asText().equals("pending")
                    && !body.path("status").asText().equals("running")) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the run never finished");
    }
}
