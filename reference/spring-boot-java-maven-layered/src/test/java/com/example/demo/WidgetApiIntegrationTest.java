package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole slice against a real Postgres: Flyway migration, the persistence mapping, the unique
 * index, the problem-detail error shape and the correlation id. An in-memory database would make
 * this test cheaper and would stop proving the thing it exists to prove.
 *
 * <p>It names no type from the application — only JSON, HTTP status codes and a table. That is
 * why it is the same file in all three architectures: an integration test written against
 * {@code CreateWidgetRequest} has to be rewritten when that class moves package, which would make
 * the architecture option a change to the database recipe. Written against the contract a client
 * actually sees, it is also a better test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WidgetApiIntegrationTest extends PostgresTestBase {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcClient database;

    @BeforeEach
    void clean() {
        database.sql("truncate table widgets restart identity").update();
    }

    @Test
    @DisplayName("a widget can be created, read back and restocked")
    void createReadRestock() {
        ResponseEntity<JsonNode> created =
                http.postForEntity("/api/widgets", Map.of("name", "flux capacitor", "quantity", 2), JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().path("id").isNumber()).isTrue();
        assertThat(created.getBody().path("createdAt").asText()).isNotBlank();

        long id = created.getBody().path("id").asLong();
        assertThat(created.getHeaders().getLocation()).hasToString("/api/widgets/" + id);

        ResponseEntity<JsonNode> restocked =
                http.postForEntity("/api/widgets/{id}/restock", Map.of("amount", 5), JsonNode.class, id);
        assertThat(restocked.getBody()).isNotNull();
        assertThat(restocked.getBody().path("quantity").asInt()).isEqualTo(7);

        assertThat(http.getForObject("/api/widgets/{id}", JsonNode.class, id)
                        .path("name")
                        .asText())
                .isEqualTo("flux capacitor");
    }

    @Test
    @DisplayName("a duplicate name is a 409 problem document, not a constraint-violation stack trace")
    void duplicateNameIsConflict() {
        http.postForEntity("/api/widgets", Map.of("name", "flux capacitor", "quantity", 1), JsonNode.class);

        ResponseEntity<ProblemDetail> conflict = http.postForEntity(
                "/api/widgets", Map.of("name", "flux capacitor", "quantity", 1), ProblemDetail.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).isNotNull();
        assertThat(conflict.getBody().getTitle()).isEqualTo("Duplicate widget name");
        assertThat(conflict.getBody().getProperties()).containsKey("correlationId");
    }

    @Test
    @DisplayName("an unknown id is a 404 problem document")
    void unknownIdIsNotFound() {
        ResponseEntity<ProblemDetail> missing = http.getForEntity("/api/widgets/{id}", ProblemDetail.class, 999_999L);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).isNotNull();
        assertThat(missing.getBody().getDetail()).contains("999999");
    }

    @Test
    @DisplayName("a caller-supplied correlation id is echoed back")
    void echoesCorrelationId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "trace-from-caller");

        ResponseEntity<String> response =
                http.exchange("/api/widgets", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("trace-from-caller");
    }

    @Test
    @DisplayName("the health endpoint reports UP")
    void healthIsUp() {
        assertThat(http.getForObject("/actuator/health", String.class)).contains("\"status\":\"UP\"");
    }
}
