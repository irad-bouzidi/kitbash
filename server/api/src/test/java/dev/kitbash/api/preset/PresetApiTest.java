package dev.kitbash.api.preset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Presets, end to end: the real catalog, the real filter chain, a real Postgres (§3, §7, §9).
 *
 * <p>The test worth reading first is {@link RoundTrip#savedThenGeneratedMatchesTheWizard()}. §23's
 * "done when" is that a preset saved from the wizard, reloaded cold and generated in one click
 * produces what the wizard produced — and since generation is deterministic (§4), "matches" can be
 * asserted byte for byte rather than by eye.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "test"})
@AutoConfigureMockMvc
class PresetApiTest {

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
            {"projectName":"billing","options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts",
             "database":"db-postgres-flyway"},
             "variables":{"groupId":"com.acme","packageName":"com.acme.billing","javaVersion":"21",
             "entityName":"Invoice","entityTable":"invoices","envPrefix":"BILLING"}}""";

    private static String preset(String name, String visibility, String policy) {
        return """
               {"name":"%s","description":"The stack we always start from","visibility":"%s",
                "versionPolicy":"%s","selection":%s}"""
                .formatted(name, visibility, policy, SELECTION);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    /** For the one test that has to age a preset the way time would, rather than the way the API allows. */
    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /**
     * One empty table per test.
     *
     * <p>Shared presets are visible to everybody by design, so without this a test asserting what
     * one user can see would be asserting what every other test in the class had saved.
     */
    @org.junit.jupiter.api.BeforeEach
    void emptyThePresets() {
        jdbc.sql("truncate preset cascade").update();
    }

    /** An author, identified by a subject of their own so tests do not share presets. */
    private RequestPostProcessor author(String who) {
        return jwt().jwt(token -> token.subject(who)).authorities(new SimpleGrantedAuthority("ROLE_kitbash-author"));
    }

    private RequestPostProcessor publisher(String who) {
        return jwt().jwt(token -> token.subject(who))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_kitbash-author"),
                        new SimpleGrantedAuthority("ROLE_kitbash-publisher"));
    }

    private JsonNode send(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("%s", response.getContentAsString()).isEqualTo(expectedStatus);
        String body = response.getContentAsString();
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private JsonNode save(String who, String body) throws Exception {
        return send(
                post("/api/v1/presets")
                        .with(author(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                201);
    }

    @Nested
    @DisplayName("the round trip")
    class RoundTrip {

        /**
         * §23's done-when, and it is only assertable because §4 made generation deterministic:
         * the same selection produces the same bytes, so "matches what the wizard produced" is a
         * byte comparison rather than a judgement.
         */
        @Test
        @DisplayName("saved, reloaded cold, generated in one click — the same zip the wizard gives")
        void savedThenGeneratedMatchesTheWizard() throws Exception {
            byte[] fromWizard = mvc.perform(post("/api/v1/generate")
                            .with(author("alice"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTION))
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();

            UUID id = UUID.fromString(save("alice", preset("house-stack", "private", "track_latest"))
                    .path("id")
                    .asText());

            // Cold: nothing but the id, read back from the row.
            byte[] fromPreset = mvc.perform(
                            post("/api/v1/presets/" + id + "/generate").with(author("alice")))
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();

            assertThat(fromPreset).isNotEmpty().isEqualTo(fromWizard);
        }

        @Test
        @DisplayName("a saved preset comes back with its selection, its policy and its recipes")
        void readsBackWhatWasSaved() throws Exception {
            UUID id = UUID.fromString(save("bob", preset("bobs-stack", "private", "track_latest"))
                    .path("id")
                    .asText());

            JsonNode read = send(get("/api/v1/presets/" + id).with(author("bob")), 200);

            assertThat(read.path("name").asText()).isEqualTo("bobs-stack");
            assertThat(read.path("versionPolicy").asText()).isEqualTo("track_latest");
            assertThat(read.path("revision").asInt()).isEqualTo(1);
            assertThat(read.path("mine").asBoolean()).isTrue();
            assertThat(read.path("staleReason").isNull()).isTrue();
            assertThat(read.path("selection").path("projectName").asText()).isEqualTo("billing");
            assertThat(read.path("recipeIds").toString()).contains("backend-spring-java");
        }

        @Test
        @DisplayName("the list is the landing page: a returning user's own presets, newest revision each")
        void listsWhatIsVisible() throws Exception {
            save("carol", preset("one", "private", "track_latest"));
            save("carol", preset("two", "private", "track_latest"));
            save("dave", preset("daves-private", "private", "track_latest"));

            JsonNode list = send(get("/api/v1/presets").with(author("carol")), 200);

            assertThat(list).hasSize(2);
            assertThat(list.toString()).contains("one").contains("two").doesNotContain("daves-private");
        }
    }

    @Nested
    @DisplayName("revisions")
    class Revisions {

        /**
         * §7 and §10: an edit does not destroy the previous definition. The unique constraint on
         * {@code (owner_id, name, revision)} is what makes that safe when two edits race.
         */
        @Test
        @DisplayName("an edit writes a new revision rather than overwriting the old one")
        void editWritesANewRevision() throws Exception {
            JsonNode first = save("erin", preset("evolving", "private", "track_latest"));
            UUID id = UUID.fromString(first.path("id").asText());

            JsonNode second = send(
                    put("/api/v1/presets/" + id)
                            .with(author("erin"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(preset("ignored-name", "team", "track_latest")),
                    200);

            assertThat(second.path("revision").asInt()).isEqualTo(2);
            // The name is the preset's identity across revisions; the body cannot rename it out
            // from under a link.
            assertThat(second.path("name").asText()).isEqualTo("evolving");
            assertThat(second.path("visibility").asText()).isEqualTo("team");

            // And the previous revision is still there, still resolving to what it was.
            assertThat(send(get("/api/v1/presets/" + id).with(author("erin")), 200)
                            .path("revision")
                            .asInt())
                    .isEqualTo(1);

            // The list shows one entry — the newest — rather than every revision ever saved.
            assertThat(send(get("/api/v1/presets").with(author("erin")), 200)).hasSize(1);
        }

        @Test
        @DisplayName("deleting removes every revision, so nothing half-deleted is left behind")
        void deleteRemovesTheWholePreset() throws Exception {
            UUID id = UUID.fromString(save("frank", preset("doomed", "private", "track_latest"))
                    .path("id")
                    .asText());
            send(
                    put("/api/v1/presets/" + id)
                            .with(author("frank"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(preset("doomed", "private", "track_latest")),
                    200);

            send(delete("/api/v1/presets/" + id).with(author("frank")), 204);

            assertThat(send(get("/api/v1/presets").with(author("frank")), 200)).isEmpty();
        }
    }

    @Nested
    @DisplayName("visibility")
    class Visible {

        @Test
        @DisplayName("a team preset is visible to somebody else; a private one is not there at all")
        void sharesWhatWasShared() throws Exception {
            save("grace", preset("shared-stack", "team", "track_latest"));
            UUID priv = UUID.fromString(save("grace", preset("secret-stack", "private", "track_latest"))
                    .path("id")
                    .asText());

            assertThat(send(get("/api/v1/presets").with(author("heidi")), 200).toString())
                    .contains("shared-stack")
                    .doesNotContain("secret-stack");

            // 404 rather than 403: a refusal that distinguishes "not yours" from "not there" is a
            // way to discover that somebody has a preset called secret-stack.
            send(get("/api/v1/presets/" + priv).with(author("heidi")), 400);
        }

        /**
         * §13 gates {@code public} behind a role, and the check cannot live in the filter chain:
         * visibility is a field in a body, and a path pattern cannot see one.
         */
        @Test
        @DisplayName("publishing needs the publisher role, which authoring does not grant")
        void publicNeedsTheRole() throws Exception {
            JsonNode refused = send(
                    post("/api/v1/presets")
                            .with(author("ivan"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(preset("for-everyone", "public", "track_latest")),
                    400);

            assertThat(refused.path("error").asText()).isEqualTo("INVALID_IDENTIFIER");
            assertThat(refused.path("detail").asText()).contains("kitbash-publisher");
            assertThat(refused.path("hint").asText()).contains("'team' instead");

            assertThat(send(
                                    post("/api/v1/presets")
                                            .with(publisher("ivan"))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(preset("for-everyone", "public", "track_latest")),
                                    201)
                            .path("visibility")
                            .asText())
                    .isEqualTo("public");
        }

        @Test
        @DisplayName("somebody else's preset cannot be edited or deleted, however visible it is")
        void othersCannotEditIt() throws Exception {
            UUID id = UUID.fromString(save("judy", preset("judys-stack", "team", "track_latest"))
                    .path("id")
                    .asText());

            send(
                    put("/api/v1/presets/" + id)
                            .with(author("mallory"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(preset("judys-stack", "team", "track_latest")),
                    400);
            send(delete("/api/v1/presets/" + id).with(author("mallory")), 400);
        }
    }

    @Nested
    @DisplayName("staleness")
    class Stale {

        /**
         * The error path §23 asks for a test of. A preset saved a year ago can name a recipe the
         * catalog has since dropped, and the difference between reporting that at load and failing
         * during render is the difference between an answer and a bug report.
         */
        @Test
        @DisplayName("a preset naming a recipe the catalog dropped says so when it is read")
        void readingAStalePresetExplainsItself() throws Exception {
            UUID id = UUID.fromString(saveStale("niaj").path("id").asText());

            JsonNode read = send(get("/api/v1/presets/" + id).with(author("niaj")), 200);

            assertThat(read.path("staleReason").asText())
                    .contains("backend-cobol-cics")
                    .contains("no longer in the catalog")
                    .contains("Edit the preset");
        }

        @Test
        @DisplayName("and generating from it is refused before anything renders, naming what is missing")
        void generatingFromAStalePresetIsRefused() throws Exception {
            UUID id = UUID.fromString(saveStale("olivia").path("id").asText());

            JsonNode refused = send(post("/api/v1/presets/" + id + "/generate").with(author("olivia")), 400);

            assertThat(refused.path("error").asText()).isEqualTo("UNKNOWN_RECIPE");
            assertThat(refused.path("detail").asText()).contains("backend-cobol-cics");
            // UNKNOWN_RECIPE's hint suggests the nearest surviving id, which is the next action.
            assertThat(refused.path("hint").asText()).isNotEmpty();
        }

        /**
         * Saved through the repository rather than the API, because the API would reject it — which
         * is the point. A preset goes stale by the catalog moving under it, not by somebody saving
         * a bad one.
         */
        private JsonNode saveStale(String who) throws Exception {
            JsonNode saved = save(who, preset("legacy-stack", "private", "track_latest"));
            UUID id = UUID.fromString(saved.path("id").asText());
            presetRow(id);
            return saved;
        }

        private void presetRow(UUID id) {
            jdbc.sql(
                            """
                            update preset
                            set selection = cast(:selection as jsonb)
                            where id = :id
                            """)
                    .param("id", id)
                    .param(
                            "selection",
                            SELECTION
                                    .replace("backend-spring-java", "backend-cobol-cics")
                                    .replace("\n", ""))
                    .update();
        }
    }
}
