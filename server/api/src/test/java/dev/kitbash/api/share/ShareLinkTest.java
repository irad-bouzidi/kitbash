package dev.kitbash.api.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Short links, and the ordering §8 sets for them.
 *
 * <p>The URL is the mechanism and the token is the fallback. That ordering is easy to invert by
 * accident and expensive when it is: a link that depends on a row stops working when the row
 * expires, while the URL form carries the selection itself and never does. So the tests that
 * matter most here are the ones about what happens when a token runs out, and about a shared
 * selection being re-validated on arrival rather than at Generate.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "test"})
@AutoConfigureMockMvc
class ShareLinkTest {

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

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyTheLinks() {
        jdbc.sql("truncate share_link cascade").update();
    }

    private JsonNode send(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("%s", response.getContentAsString()).isEqualTo(expectedStatus);
        String body = response.getContentAsString();
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private String share(String body) throws Exception {
        return send(
                        post("/api/v1/share")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        201)
                .path("token")
                .asText();
    }

    @Nested
    @DisplayName("the token")
    class Token {

        @Test
        @DisplayName("a selection goes in and the same one comes back out")
        void roundTrips() throws Exception {
            String token = share(SELECTION);

            JsonNode shared = send(get("/api/v1/share/" + token).with(jwt()), 200);

            assertThat(shared.path("selection").path("projectName").asText()).isEqualTo("shared-service");
            assertThat(shared.path("selection").path("options").path("backend").asText())
                    .isEqualTo("backend-spring-java");
            assertThat(shared.path("selection")
                            .path("variables")
                            .path("packageName")
                            .asText())
                    .isEqualTo("com.acme.shared");
            // Migrated on the way out, so it always arrives as the current schema (kitbash-6).
            assertThat(shared.path("selection").path("schemaVersion").asInt()).isEqualTo(1);
        }

        /**
         * Short because the whole reason to mint one is that the URL was too long to paste. Trading
         * one unwieldy string for another would be no trade at all.
         */
        @Test
        @DisplayName("is short enough to paste and read aloud")
        void isShort() throws Exception {
            assertThat(share(SELECTION)).hasSize(11).matches("[0-9a-hjkmnp-tv-z]+");
        }

        /**
         * The token <i>is</i> the capability: anybody holding it can read the selection. A
         * sequential id or a timestamp would let somebody walk the space.
         */
        @Test
        @DisplayName("is unpredictable, because holding one is the whole permission")
        void isUnpredictable() throws Exception {
            Set<String> tokens = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                tokens.add(share(SELECTION));
            }

            assertThat(tokens).hasSize(20);
        }

        @Test
        @DisplayName("a token nobody minted is not there, and neither is a path that is not a token")
        void refusesNonsense() throws Exception {
            // 404 since kitbash-39. A well-formed token for a link nobody minted is not a
            // malformed request, and answering 400 told the caller they had sent something wrong
            // when they had not.
            send(get("/api/v1/share/abcdefghjkm").with(jwt()), 404);
            // The same answer, though this one is rejected on shape before the database is asked
            // at all — which is the point. A caller who could tell the two apart by status could
            // use this endpoint to learn which token shapes are real.
            send(get("/api/v1/share/not-a-token-at-all").with(jwt()), 404);
        }

        /**
         * §8's ordering, in the one place a user meets it. A token expiring is not a lost
         * configuration, because the URL form never depended on this row — and the message says so
         * rather than leaving somebody to work it out.
         */
        @Test
        @DisplayName("an expired token says what to ask for instead, rather than just going missing")
        void expiryExplainsItself() throws Exception {
            String token = share(SELECTION);
            jdbc.sql("update share_link set expires_at = now() - interval '1 day' where token = :token")
                    .param("token", token)
                    .update();

            JsonNode refused = send(get("/api/v1/share/" + token).with(jwt()), 400);

            assertThat(refused.path("detail").asText()).contains("has expired");
            assertThat(refused.path("hint").asText()).contains("URL form").contains("never expires");
        }
    }

    @Nested
    @DisplayName("what is stored")
    class Stored {

        /**
         * A link that resolves to something the generator would refuse is a link that fails in
         * somebody else's hands, and the person who made it is the one who can fix it.
         */
        @Test
        @DisplayName("a selection the generator would refuse is refused at sharing time")
        void refusesAnUnshareableSelection() throws Exception {
            JsonNode refused = send(
                    post("/api/v1/share")
                            .with(jwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTION.replace("backend-spring-java", "backend-spring-jva")),
                    400);

            assertThat(refused.path("error").asText()).isEqualTo("UNKNOWN_RECIPE");
            assertThat(jdbc.sql("select count(*) from share_link")
                            .query(Integer.class)
                            .single())
                    .as("a refused share still wrote a row")
                    .isZero();
        }

        /**
         * §25: opening a shared configuration re-validates against the <i>current</i> catalog, so a
         * selection that has since become invalid says so on arrival rather than at Generate. The
         * share endpoint hands back the selection; this asserts the other half — that feeding it
         * straight to /validate is what the wizard does and that the answer is the real one.
         */
        @Test
        @DisplayName("a shared selection validates through the same path the wizard uses")
        void reValidatesOnArrival() throws Exception {
            String token = share(SELECTION);
            JsonNode shared = send(get("/api/v1/share/" + token).with(jwt()), 200);

            JsonNode resolution = send(
                    post("/api/v1/validate")
                            .with(jwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(shared.path("selection"))),
                    200);

            assertThat(resolution.path("valid").asBoolean()).isTrue();
            assertThat(resolution.path("recipes").toString()).contains("backend-spring-java");
        }
    }
}
