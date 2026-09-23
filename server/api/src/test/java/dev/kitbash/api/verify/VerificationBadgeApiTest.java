package dev.kitbash.api.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/verification} on the wire.
 *
 * <p>No persistence profile, deliberately. Badges are a file the nightly published, and a
 * deployment with no database should still tell a user which combinations are known to be red —
 * that is the half of §9 that matters while choosing, and gating it on infrastructure it does not
 * use would be gating information on an accident.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class VerificationBadgeApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("badges are served without a database, and carry the catalog they belong to")
    void servedWithoutPersistence() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/verification")
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.path("catalogDigest").asText()).startsWith("sha256:");
        assertThat(body.has("choices")).isTrue();
        assertThat(body.has("pairs")).isTrue();
    }

    @Test
    @DisplayName("the entity tag revalidates, so badges are read on every load and sent once")
    void revalidatesByTag() throws Exception {
        String eTag = mvc.perform(get("/api/v1/verification")
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        assertThat(eTag).isNotBlank();

        assertThat(mvc.perform(get("/api/v1/verification")
                                .header(HttpHeaders.IF_NONE_MATCH, eTag)
                                .with(jwt().jwt(token ->
                                        token.subject(UUID.randomUUID().toString()))))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(304);
    }

    @Test
    @DisplayName("a real run of the real matrix produces real badges for the running catalog")
    void servesWhatTheMatrixActuallyWrote() throws Exception {
        // Not a fixture. The runner writes verification/build/verification.json when the matrix
        // runs, this reads that file through Repository.locate(), and the two halves live in
        // different modules — so the one failure worth catching here is the two of them
        // disagreeing about the document's shape, which no fixture on either side would notice.
        JsonNode body = JSON.readTree(mvc.perform(get("/api/v1/verification")
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andReturn()
                .getResponse()
                .getContentAsString());

        assumeThat(body.path("staleFor").isNull())
                .as("the checked-out results belong to another catalog; nothing to assert here")
                .isTrue();
        assumeThat(body.path("generatedAt").isNull())
                .as("no matrix has been run in this working tree")
                .isFalse();

        assertThat(body.path("pairs"))
                .as("a run that covered a multi-option selection has pairings to badge")
                .isNotEmpty();
        assertThat(body.path("pairs").get(0).path("key").asText())
                .as("a pairing key is two chosen values, which is what the client builds too")
                .contains(" & ");
        assertThat(body.path("choices")).isNotEmpty();
    }

    @Test
    @DisplayName("a cell the published run does not name has no log, whatever the id looks like")
    void refusesALogForAnUnpublishedCell() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/verification/cells/{id}/log", "no-such-cell")
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(JSON.readTree(response.getContentAsString()).path("error").asText())
                .isEqualTo("VERIFY_CELL_NOT_FOUND");
    }

    @Test
    @DisplayName("an id that tries to be a path is refused by the same rule, not by a second one")
    void anIdIsNotAPath() throws Exception {
        // Checked against the published run rather than sanitised: a cell the results do not name
        // has no log worth serving, and an id that cannot reach the filesystem cannot traverse it.
        assertThat(mvc.perform(get("/api/v1/verification/cells/{id}/log", "..%2F..%2Fapplication")
                                .with(jwt().jwt(token ->
                                        token.subject(UUID.randomUUID().toString()))))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("badges are closed to anonymous callers, like everything else (§13)")
    void closedWithoutAToken() throws Exception {
        assertThat(mvc.perform(get("/api/v1/verification"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
