package com.example.demo

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles

/**
 * The whole slice against a real Postgres: Flyway migration, the persistence mapping, the unique
 * index, the problem-detail error shape and the correlation id. An in-memory database would make
 * this test cheaper and would stop proving the thing it exists to prove.
 *
 * It names no type from the application — only JSON, HTTP status codes and a table. That is why
 * it is the same file in all three architectures: an integration test written against
 * `CreateWidgetRequest` has to be rewritten when that class moves package, which would make the
 * architecture option a change to the database recipe. Written against the contract a client
 * actually sees, it is also a better test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WidgetApiIntegrationTest : PostgresTestBase() {
    @Autowired
    private lateinit var http: TestRestTemplate

    @Autowired
    private lateinit var database: JdbcClient

    @BeforeEach
    fun clean() {
        database.sql("truncate table widgets restart identity").update()
    }

    @Test
    @DisplayName("a widget can be created, read back and restocked")
    fun createReadRestock() {
        val created =
            http.postForEntity(
                "/api/widgets",
                mapOf("name" to "flux capacitor", "quantity" to 2),
                JsonNode::class.java,
            )

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(created.body).isNotNull
        assertThat(created.body!!.path("id").isNumber).isTrue
        assertThat(created.body!!.path("createdAt").asText()).isNotBlank

        val id = created.body!!.path("id").asLong()
        assertThat(created.headers.location).hasToString("/api/widgets/$id")

        val restocked =
            http.postForEntity("/api/widgets/{id}/restock", mapOf("amount" to 5), JsonNode::class.java, id)
        assertThat(restocked.body).isNotNull
        assertThat(restocked.body!!.path("quantity").asInt()).isEqualTo(7)

        assertThat(http.getForObject("/api/widgets/{id}", JsonNode::class.java, id).path("name").asText())
            .isEqualTo("flux capacitor")
    }

    @Test
    @DisplayName("a duplicate name is a 409 problem document, not a constraint-violation stack trace")
    fun duplicateNameIsConflict() {
        http.postForEntity("/api/widgets", mapOf("name" to "flux capacitor", "quantity" to 1), JsonNode::class.java)

        val conflict =
            http.postForEntity(
                "/api/widgets",
                mapOf("name" to "flux capacitor", "quantity" to 1),
                ProblemDetail::class.java,
            )

        assertThat(conflict.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(conflict.body).isNotNull
        assertThat(conflict.body!!.title).isEqualTo("Duplicate widget name")
        assertThat(conflict.body!!.properties).containsKey("correlationId")
    }

    @Test
    @DisplayName("an unknown id is a 404 problem document")
    fun unknownIdIsNotFound() {
        val missing = http.getForEntity("/api/widgets/{id}", ProblemDetail::class.java, 999_999L)

        assertThat(missing.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(missing.body).isNotNull
        assertThat(missing.body!!.detail).contains("999999")
    }

    @Test
    @DisplayName("a caller-supplied correlation id is echoed back")
    fun echoesCorrelationId() {
        val headers = HttpHeaders()
        headers.set("X-Correlation-Id", "trace-from-caller")

        val response =
            http.exchange("/api/widgets", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertThat(response.headers.getFirst("X-Correlation-Id")).isEqualTo("trace-from-caller")
    }

    @Test
    @DisplayName("the health endpoint reports UP")
    fun healthIsUp() {
        assertThat(http.getForObject("/actuator/health", String::class.java)).contains("\"status\":\"UP\"")
    }
}
