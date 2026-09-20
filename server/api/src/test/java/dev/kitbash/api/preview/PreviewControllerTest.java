package dev.kitbash.api.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Seeing the tree before downloading it (§6, §8, §9).
 *
 * <p>The claim §26 makes for this feature is trust: a generator you cannot look inside is one you
 * try once. So the test that matters most is {@link #showsRenderedContentNotTheTemplate()} — a
 * preview that showed {@code {{ packageName }}} would be showing the generator's internals rather
 * than the project somebody is about to be handed.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser
@AutoConfigureMockMvc
class PreviewControllerTest {

    private static final String SELECTION =
            """
            {"projectName":"billing","options":{"backend":"backend-spring-java",
             "buildTool":"build-gradle-kts","database":"db-postgres-flyway"},
             "variables":{"groupId":"com.acme","packageName":"com.acme.billing","javaVersion":"21",
             "entityName":"Invoice","entityTable":"invoices","envPrefix":"BILLING"}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private JsonNode send(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("%s", response.getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(response.getContentAsString());
    }

    private JsonNode preview(String query) throws Exception {
        String url = query.isEmpty() ? "/api/v1/preview" : "/api/v1/preview/file" + query;
        return send(post(url).contentType(MediaType.APPLICATION_JSON).content(SELECTION), 200);
    }

    @Test
    @DisplayName("the tree is paths and sizes, and nothing that would make the payload unpredictable")
    void listsTheTree() throws Exception {
        JsonNode tree = preview("");

        assertThat(tree.path("projectName").asText()).isEqualTo("billing");
        assertThat(tree.path("catalogDigest").asText()).startsWith("sha256:");
        assertThat(tree.path("fileCount").asInt()).isPositive();
        assertThat(tree.path("totalBytes").asLong()).isPositive();

        JsonNode first = tree.path("files").get(0);
        assertThat(first.path("path").asText()).isNotEmpty();
        assertThat(first.path("bytes").asLong()).isNotNegative();
        // §26: inlining the small files would make the payload's size depend on the selection.
        assertThat(first.has("content")).isFalse();
    }

    /**
     * The whole point of the feature. A preview of the template would be a preview of the
     * generator; what builds trust is the Java somebody is about to be handed.
     */
    @Test
    @DisplayName("a file shows its real rendered content, not the template it came from")
    void showsRenderedContentNotTheTemplate() throws Exception {
        String path = "src/main/java/com/acme/billing/BillingApplication.java";

        JsonNode file = preview("?path=" + path);

        assertThat(file.path("binary").asBoolean()).isFalse();
        assertThat(file.path("content").asText())
                .contains("package com.acme.billing;")
                .contains("class BillingApplication")
                // The placeholders are gone, which is the assertion that matters.
                .doesNotContain("{{")
                .doesNotContain("packageName");
    }

    @Test
    @DisplayName("the path a preview offers is a path a preview serves")
    void everyPathInTheTreeIsFetchable() throws Exception {
        JsonNode tree = preview("");

        for (JsonNode entry : tree.path("files")) {
            String path = entry.path("path").asText();
            JsonNode file = preview("?path=" + path);
            assertThat(file.path("path").asText()).isEqualTo(path);
            assertThat(file.path("bytes").asLong())
                    .isEqualTo(entry.path("bytes").asLong());
        }
    }

    /**
     * §26: binary files are reported as binary with their size, never streamed as text. Decided by
     * decoding rather than by extension, so the answer is about the bytes rather than the name.
     */
    @Test
    @DisplayName("a binary file says so and withholds its content, rather than returning mojibake")
    void reportsBinaryFiles() throws Exception {
        JsonNode tree = preview("");
        JsonNode jar = null;
        for (JsonNode entry : tree.path("files")) {
            if (entry.path("path").asText().endsWith(".jar")) {
                jar = entry;
            }
        }

        assertThat(jar).as("the gradle wrapper jar should be in this selection").isNotNull();
        assertThat(jar.path("binary").asBoolean()).isTrue();

        JsonNode file = preview("?path=" + jar.path("path").asText());
        assertThat(file.path("binary").asBoolean()).isTrue();
        assertThat(file.path("content").isNull()).isTrue();
        assertThat(file.path("bytes").asLong()).isPositive();
    }

    @Test
    @DisplayName("a path this selection does not produce is refused with what to do instead")
    void refusesAnUnknownPath() throws Exception {
        JsonNode refused = send(
                post("/api/v1/preview/file?path=src/main/java/nowhere/Nothing.java")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION),
                400);

        assertThat(refused.path("error").asText()).isEqualTo("INVALID_IDENTIFIER");
        assertThat(refused.path("hint").asText()).contains("the tree first");
    }

    /**
     * §26's done-when. The number is generous on purpose — this asserts that the endpoint is a
     * partial pipeline run rather than something that got quietly expensive, not that a particular
     * machine is fast.
     */
    @Test
    @DisplayName("a full-stack preview renders well under a second")
    void isFast() throws Exception {
        String fullStack = SELECTION.replace(
                "\"database\":\"db-postgres-flyway\"",
                "\"database\":\"db-postgres-flyway\",\"frontend\":\"frontend-react-vite\"");

        Instant started = Instant.now();
        send(post("/api/v1/preview").contentType(MediaType.APPLICATION_JSON).content(fullStack), 200);
        Duration took = Duration.between(started, Instant.now());

        assertThat(took).isLessThan(Duration.ofSeconds(1));
    }

    /**
     * Clicking through a tree is many requests for one selection, so the pipeline runs once. The
     * observable part is the time: the second call cannot be doing the work the first one did.
     */
    @Test
    @DisplayName("clicking a second file does not re-run the pipeline")
    void reusesTheRenderedPreview() throws Exception {
        preview("");

        Instant started = Instant.now();
        preview("?path=README.md");
        preview("?path=settings.gradle.kts");
        Duration took = Duration.between(started, Instant.now());

        assertThat(took).isLessThan(Duration.ofMillis(200));
    }
}
