package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.config.CorrelationIdFilter;
import com.example.demo.controller.CreateWidgetRequest;
import com.example.demo.controller.WidgetResponse;
import com.example.demo.repository.WidgetRepository;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole slice against a real Postgres: Flyway migration, JPA mapping, the unique index, the
 * problem-detail error shape and the correlation id. An in-memory database would make this test
 * cheaper and would stop proving the thing it exists to prove.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WidgetApiIntegrationTest extends PostgresTestBase {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private WidgetRepository widgets;

    @BeforeEach
    void clean() {
        widgets.deleteAll();
    }

    @Test
    @DisplayName("a widget can be created, read back and restocked")
    void createReadRestock() {
        ResponseEntity<WidgetResponse> created =
                http.postForEntity("/api/widgets", new CreateWidgetRequest("flux capacitor", 2), WidgetResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().id()).isNotNull();
        assertThat(created.getBody().createdAt()).isNotNull();

        long id = created.getBody().id();
        assertThat(created.getHeaders().getLocation()).hasToString("/api/widgets/" + id);

        ResponseEntity<WidgetResponse> restocked =
                http.postForEntity("/api/widgets/{id}/restock", new RestockBody(5), WidgetResponse.class, id);
        assertThat(restocked.getBody()).isNotNull();
        assertThat(restocked.getBody().quantity()).isEqualTo(7);

        assertThat(http.getForObject("/api/widgets/{id}", WidgetResponse.class, id)
                        .name())
                .isEqualTo("flux capacitor");
    }

    @Test
    @DisplayName("a duplicate name is a 409 problem document, not a constraint-violation stack trace")
    void duplicateNameIsConflict() {
        http.postForEntity("/api/widgets", new CreateWidgetRequest("flux capacitor", 1), WidgetResponse.class);

        ResponseEntity<ProblemDetail> conflict =
                http.postForEntity("/api/widgets", new CreateWidgetRequest("flux capacitor", 1), ProblemDetail.class);

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
        headers.set(CorrelationIdFilter.HEADER, "trace-from-caller");

        ResponseEntity<String> response =
                http.exchange("/api/widgets", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("trace-from-caller");
    }

    @Test
    @DisplayName("the health endpoint reports UP")
    void healthIsUp() {
        assertThat(http.getForObject("/actuator/health", String.class)).contains("\"status\":\"UP\"");
    }

    private record RestockBody(int amount) {}
}
