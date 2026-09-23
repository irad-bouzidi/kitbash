package dev.kitbash.api.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Generations as receipts (§3, §7, §10, §24).
 *
 * <p>The test worth reading first is {@link Replay#exactReplayReproducesTheZip()}: §24's done-when
 * is that an exact replay of an old generation reproduces its zip byte for byte, and that is only
 * assertable because §4 made generation deterministic. Everything else here exists to keep the two
 * replay modes from quietly becoming one.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "test"})
@AutoConfigureMockMvc
class GenerationHistoryTest {

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
            {"projectName":"billing","options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts"},
             "variables":{"groupId":"com.acme","packageName":"com.acme.billing","javaVersion":"21",
             "entityName":"Invoice","entityTable":"invoices","envPrefix":"BILLING"}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyTheHistory() {
        jdbc.sql("truncate generation cascade").update();
    }

    private RequestPostProcessor as(String who) {
        return jwt().jwt(token -> token.subject(who));
    }

    private JsonNode send(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("%s", response.getContentAsString()).isEqualTo(expectedStatus);
        String body = response.getContentAsString();
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    /** Generates once, and returns the bytes. The row is written after the response. */
    private byte[] generate(String who) throws Exception {
        return mvc.perform(post("/api/v1/generate")
                        .with(as(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    private UUID onlyGeneration(String who) throws Exception {
        JsonNode history = send(get("/api/v1/generations").with(as(who)), 200);
        assertThat(history).hasSize(1);
        return UUID.fromString(history.get(0).path("id").asText());
    }

    @Nested
    @DisplayName("the receipt")
    class Receipt {

        @Test
        @DisplayName("every generation is recorded with its lock, its digest and what it cost")
        void recordsTheLock() throws Exception {
            byte[] zip = generate("alice");

            JsonNode row =
                    send(get("/api/v1/generations/" + onlyGeneration("alice")).with(as("alice")), 200);

            assertThat(row.path("status").asText()).isEqualTo("succeeded");
            assertThat(row.path("projectName").asText()).isEqualTo("billing");
            assertThat(row.path("sizeBytes").asInt()).isEqualTo(zip.length);
            assertThat(row.path("durationMillis").asInt()).isPositive();
            assertThat(row.path("catalogDigest").asText()).startsWith("sha256:");
            // The valuable part (§7): recipe id to the exact version that produced this.
            assertThat(row.path("lock").path("backend-spring-java").asText()).isNotEmpty();
            assertThat(row.path("lock").path("base").asText()).isNotEmpty();
        }

        /**
         * §24 asks for this by name: a history that only contains successes cannot answer "why did
         * this break", which is the question people actually bring to a history page.
         */
        @Test
        @DisplayName("a failed generation is a row too, carrying the §14 code that explains it")
        void recordsFailures() throws Exception {
            mvc.perform(post("/api/v1/generate")
                            .with(as("bob"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTION.replace("backend-spring-java", "backend-spring-jva")))
                    .andReturn();

            JsonNode history = send(get("/api/v1/generations").with(as("bob")), 200);

            assertThat(history).hasSize(1);
            assertThat(history.get(0).path("status").asText()).isEqualTo("failed");
            assertThat(history.get(0).path("lock")).isEmpty();
        }

        @Test
        @DisplayName("a history row is personal: somebody else's is not there at all")
        void historyIsPersonal() throws Exception {
            generate("carol");
            UUID id = onlyGeneration("carol");

            assertThat(send(get("/api/v1/generations").with(as("dave")), 200)).isEmpty();
            // 404, not 403: distinguishing "not yours" from "not there" is a way to count
            // somebody else's generations.
            send(get("/api/v1/generations/" + id).with(as("dave")), 404);
        }

        @Test
        @DisplayName("keeping a row clears its expiry, which is how §10 says it rather than a second policy")
        void keepingClearsTheExpiry() throws Exception {
            generate("erin");
            UUID id = onlyGeneration("erin");
            assertThat(send(get("/api/v1/generations/" + id).with(as("erin")), 200)
                            .path("expiresAt")
                            .isNull())
                    .isFalse();

            JsonNode kept = send(post("/api/v1/generations/" + id + "/keep").with(as("erin")), 200);

            assertThat(kept.path("kept").asBoolean()).isTrue();
            assertThat(kept.path("expiresAt").isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        /**
         * §24's done-when. Only assertable because §4 made generation deterministic: the same
         * selection against the same catalog is the same bytes, so an exact replay <i>reproduces</i>
         * rather than approximates.
         */
        @Test
        @DisplayName("an exact replay reproduces the zip, byte for byte")
        void exactReplayReproducesTheZip() throws Exception {
            byte[] original = generate("frank");
            UUID id = onlyGeneration("frank");

            JsonNode replay = send(
                    post("/api/v1/generations/" + id + "/replay?mode=exact").with(as("frank")), 200);
            assertThat(replay.path("mode").asText()).isEqualTo("exact");
            assertThat(replay.path("catalogMoved").asBoolean()).isFalse();
            assertThat(replay.path("summary").asText()).isEqualTo("no change");

            byte[] again = mvc.perform(
                            post("/api/v1/generations/" + id + "/download").with(as("frank")))
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();

            assertThat(again).isEqualTo(original);
        }

        /**
         * The other half of the promise. §24 requires exact replay to fail loudly and usefully when
         * a locked version is gone — "usefully" meaning the message names {@code recipe@version},
         * because the reader has to decide between checking out an older catalog and accepting a
         * current replay, and neither decision can be made from "unavailable".
         */
        @Test
        @DisplayName("an exact replay of a lock the catalog has moved past is refused, by name")
        void exactReplayRefusesWhenTheCatalogMoved() throws Exception {
            generate("grace");
            UUID id = onlyGeneration("grace");
            ageTheLock(id);

            JsonNode refused = send(
                    post("/api/v1/generations/" + id + "/replay?mode=exact").with(as("grace")), 400);

            assertThat(refused.path("detail").asText()).contains("backend-spring-java@0.9.0");
            assertThat(refused.path("hint").asText()).contains("mode=current");
        }

        @Test
        @DisplayName("a current replay proceeds, and says exactly what moved")
        void currentReplayReportsTheDrift() throws Exception {
            generate("heidi");
            UUID id = onlyGeneration("heidi");
            ageTheLock(id);

            JsonNode replay = send(
                    post("/api/v1/generations/" + id + "/replay?mode=current").with(as("heidi")), 200);

            assertThat(replay.path("mode").asText()).isEqualTo("current");
            assertThat(replay.path("catalogMoved").asBoolean()).isTrue();
            assertThat(replay.path("summary").asText()).contains("backend-spring-java 0.9.0→");
            assertThat(replay.path("changes")).hasSize(1);
        }

        @Test
        @DisplayName("the row says up front whether an exact replay is still possible")
        void theRowSaysWhetherExactIsPossible() throws Exception {
            generate("ivan");
            UUID id = onlyGeneration("ivan");

            assertThat(send(get("/api/v1/generations/" + id).with(as("ivan")), 200)
                            .path("exactlyReproducible")
                            .asBoolean())
                    .isTrue();

            ageTheLock(id);

            // A button that might fail is worse than one that says why, so this is answered when
            // the row is read rather than when somebody clicks.
            assertThat(send(get("/api/v1/generations/" + id).with(as("ivan")), 200)
                            .path("exactlyReproducible")
                            .asBoolean())
                    .isFalse();
        }

        @Test
        @DisplayName("an unknown mode is refused with both of the real ones named")
        void refusesAnUnknownMode() throws Exception {
            generate("judy");
            UUID id = onlyGeneration("judy");

            JsonNode refused = send(
                    post("/api/v1/generations/" + id + "/replay?mode=approximately")
                            .with(as("judy")),
                    400);

            assertThat(refused.path("detail").asText()).contains("'exact' or 'current'");
        }
    }

    @Nested
    @DisplayName("the lock diff")
    class Diff {

        /**
         * §7 calls this the debugging tool, and §24's done-when is that two generations from
         * different catalog digests can be diffed by their locks. This is that, end to end.
         */
        @Test
        @DisplayName("two generations from different catalogs diff to the versions that moved")
        void diffsTwoGenerations() throws Exception {
            generate("ken");
            UUID older = onlyGeneration("ken");
            ageTheLock(older);

            generate("ken");
            UUID newer = send(get("/api/v1/generations").with(as("ken")), 200)
                    .valueStream()
                    .map(row -> UUID.fromString(row.path("id").asText()))
                    .filter(id -> !id.equals(older))
                    .findFirst()
                    .orElseThrow();

            JsonNode diff =
                    send(get("/api/v1/generations/" + older + "/diff/" + newer).with(as("ken")), 200);

            assertThat(diff.path("changed")).hasSize(1);
            assertThat(diff.path("changed").get(0).path("recipeId").asText()).isEqualTo("backend-spring-java");
            assertThat(diff.path("changed").get(0).path("before").asText()).isEqualTo("0.9.0");
            assertThat(diff.path("added")).isEmpty();
            assertThat(diff.path("removed")).isEmpty();
        }
    }

    /**
     * Rewrites a row's lock to a version the catalog does not have, which is what the passage of
     * time does to a real one. Done through the database rather than the API because the API
     * cannot produce it — a generation locks whatever the catalog held when it ran.
     */
    private void ageTheLock(UUID id) {
        jdbc.sql(
                        """
                        update generation
                        set lock = jsonb_set(lock, '{backend-spring-java}', '"0.9.0"'),
                            catalog_digest = 'sha256:an-older-catalog'
                        where id = :id
                        """)
                .param("id", id)
                .update();
    }
}
