package dev.kitbash.api.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Minting links is rate limited on its own budget (§13).
 *
 * <p>A class of its own, with its own budget, for the same reason {@code CacheHitsAreFreeTest} is:
 * a limit small enough to trip on purpose is a limit every other test in the file would trip by
 * accident.
 *
 * <p>Its own budget rather than {@code /generate}'s, because the two costs differ by orders —
 * minting a token is a row, rendering a project is a render. One budget for both would either
 * throttle generation to protect a cheap endpoint or fail to throttle the cheap one at all.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "test"})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"kitbash.limits.share-per-minute=2", "kitbash.limits.share-burst=2"})
class ShareRateLimitTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String SELECTION =
            """
            {"projectName":"shared-service","options":{"backend":"backend-spring-java",
             "buildTool":"build-gradle-kts"},
             "variables":{"groupId":"com.acme","packageName":"com.acme.shared","javaVersion":"21",
             "entityName":"Widget","entityTable":"widgets","envPrefix":"SHARED"}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    /**
     * The hint is asserted, not just the status, because it is bucket-specific and was briefly
     * not: "a cache hit costs nothing" is true of generation and meaningless on a share link. A
     * hint that explains the wrong endpoint is worse than no hint at all.
     */
    @Test
    @DisplayName("a burst of links is refused, with a hint about the URL form rather than the cache")
    void refusesABurst() throws Exception {
        assertThat(mint("mallory").getStatus()).isEqualTo(201);
        assertThat(mint("mallory").getStatus()).isEqualTo(201);

        MockHttpServletResponse refused = mint("mallory");

        assertThat(refused.getStatus()).isEqualTo(429);
        JsonNode problem = json.readTree(refused.getContentAsString());
        assertThat(problem.path("error").asText()).isEqualTo("RATE_LIMITED");
        assertThat(problem.path("hint").asText()).contains("shared as a URL").doesNotContain("cache");
    }

    @Test
    @DisplayName("and one caller running out does not stop another")
    void budgetsArePerCaller() throws Exception {
        mint("nadia");
        mint("nadia");
        assertThat(mint("nadia").getStatus()).isEqualTo(429);

        assertThat(mint("oscar").getStatus()).isEqualTo(201);
    }

    private MockHttpServletResponse mint(String who) throws Exception {
        return mvc.perform(post("/api/v1/share")
                        .with(jwt().jwt(token -> token.subject(who)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION))
                .andReturn()
                .getResponse();
    }
}
