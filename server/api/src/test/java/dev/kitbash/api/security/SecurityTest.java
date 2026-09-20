package dev.kitbash.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may do what, asserted rather than configured and hoped for (§13, §18).
 *
 * <p>Every other test in this module runs as a signed-in user, which means none of them would
 * notice if the filter chain stopped closing anything. This one is the test that would.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityTest {

    private static final String SELECTION =
            """
            {"projectName":"demo","options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts"},
             "variables":{"groupId":"com.example","packageName":"com.example.demo","javaVersion":"21",
             "entityName":"Widget","entityTable":"widgets","envPrefix":"DEMO"}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("an unauthenticated generate is refused with the §14 envelope, not an empty 401")
    void unauthenticatedGenerateIsRefused() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(401);

        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.path("error").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(problem.path("title").asText()).isEqualTo("Not signed in");
        // §14: the hint names the next action. "Unauthorized" on its own does not.
        assertThat(problem.path("hint").asText()).contains("Sign in");
    }

    @Test
    @DisplayName("reading the catalog needs a token too, because the catalog is not public")
    void unauthenticatedMetadataIsRefused() throws Exception {
        assertThat(mvc.perform(get("/api/v1/metadata"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    /**
     * The one exception, and it has to be. A health check that needed SSO could not report that
     * SSO is down, and an orchestrator would kill a service whose only fault was that its identity
     * provider had hiccupped.
     */
    @Test
    @DisplayName("health answers without a token, and it is the only thing that does")
    void healthStaysOpen() throws Exception {
        assertThat(mvc.perform(get("/actuator/health"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        assertThat(mvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus())
                .as("/actuator/info carries the catalog digest, which is not public")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("any authenticated user may read the catalog and generate — no role needed")
    void generatingNeedsNoRole() throws Exception {
        assertThat(mvc.perform(get("/api/v1/metadata").with(jwt()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        assertThat(mvc.perform(post("/api/v1/generate")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(SELECTION))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("generation is the product; gating it behind a role means onboarding twice")
                .isEqualTo(200);
    }

    /**
     * The preset endpoints arrive with {@code kitbash-23}. The rule is here now, so a 403 is
     * asserted against the filter chain rather than against a controller that does not exist — and
     * so {@code kitbash-23} inherits a rule that is already tested.
     */
    @Test
    @DisplayName("writing a preset without the role is a 403 carrying the envelope")
    void writingAPresetNeedsARole() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/presets")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.path("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(problem.path("hint").asText()).contains("role");
    }

    @Test
    @DisplayName("with the role, the same request gets past authorisation")
    void theRoleOpensIt() throws Exception {
        int status = mvc.perform(post("/api/v1/presets")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_kitbash-author")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus();

        // Not 403: authorisation passed. The endpoint itself is kitbash-23, so what comes back is
        // "no such handler" — which is the correct answer from a filter chain that let it through.
        assertThat(status).isNotEqualTo(403).isNotEqualTo(401);
    }

    @Test
    @DisplayName("publishing needs its own role, which authoring does not grant")
    void publishingNeedsItsOwnRole() throws Exception {
        assertThat(mvc.perform(post("/api/v1/presets/01234567-89ab-cdef-0123-456789abcdef/publish")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_kitbash-author"))))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);

        assertThat(mvc.perform(post("/api/v1/presets/01234567-89ab-cdef-0123-456789abcdef/publish")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_kitbash-publisher"))))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isNotEqualTo(403);
    }
}
