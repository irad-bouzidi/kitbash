package dev.kitbash.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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

/**
 * §8 calls the metadata endpoint the hinge of the design: the wizard renders itself from this
 * document, so adding a recipe is a backend-only change. These tests are about whether that is
 * actually true — whether a client that knows nothing about Spring Boot or React could build the
 * whole form from this response alone.
 */
@SpringBootTest
@ActiveProfiles("test")
// Signed in, because since kitbash-22 every endpoint but /actuator/health is (§13). These tests
// are about what the endpoints say, not about who may call them — SecurityTest covers that.
@WithMockUser
@AutoConfigureMockMvc
class MetadataControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private JsonNode metadata() throws Exception {
        return json.readTree(
                mvc.perform(get("/api/v1/metadata")).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("carries every group, slot and choice the catalog offers")
    void describesTheWholeCatalog() throws Exception {
        JsonNode document = metadata();

        assertThat(document.path("catalogDigest").asText()).startsWith("sha256:");
        // Eleven since kitbash-32 added the observability feature.
        assertThat(document.path("recipeCount").asInt()).isEqualTo(11);
        assertThat(texts(document.path("groups"), "id")).containsExactly("stack", "delivery");

        List<String> slots = new ArrayList<>();
        document.path("groups").forEach(group -> group.path("options").forEach(option -> {
            if (option.path("availableWhen").isEmpty()) {
                slots.add(option.path("id").asText());
            }
        }));
        assertThat(slots)
                .contains("backend", "frontend", "buildTool", "database", "auth", "observability", "docker", "ci");
    }

    @Test
    @DisplayName("every option carries its own label, help and type, so nothing is hardcoded client-side")
    void everyStringComesFromTheServer() throws Exception {
        metadata().path("groups").forEach(group -> group.path("options").forEach(option -> {
            assertThat(option.path("label").asText())
                    .as("label of %s", option.path("id"))
                    .isNotEmpty();
            assertThat(option.path("help").asText())
                    .as("help of %s", option.path("id"))
                    .isNotEmpty();
            assertThat(option.path("type").asText())
                    .as("type of %s", option.path("id"))
                    .isIn("enum", "boolean", "string", "multi-select");
        }));
    }

    @Test
    @DisplayName("a recipe's own option sits beside the slot that selects it, and says when it applies")
    void recipeOptionsAreScoped() throws Exception {
        JsonNode architecture = optionById(metadata(), "architecture");

        // §9 wants an option that does not currently apply to stay visible and disabled with the
        // reason on hover, which a client can only do if it knows what it depends on.
        //
        // Both JVM backends declare `architecture`, and the wizard gets one control for the pair:
        // two would collide on the option id and leave a permanently disabled duplicate on screen
        // whichever backend was chosen.
        assertThat(texts(architecture.path("availableWhen"), null))
                .containsExactly("backend-spring-java", "backend-spring-kotlin");
        assertThat(texts(architecture.path("choices"), "value"))
                .containsExactly("layered", "hexagonal", "modular-monolith");

        // And exactly one of it, which is the part a duplicate id would have broken.
        List<String> architectures = new ArrayList<>();
        metadata().path("groups").forEach(group -> group.path("options").forEach(option -> {
            if (option.path("id").asText().equals("architecture")) {
                architectures.add(option.path("id").asText());
            }
        }));
        assertThat(architectures).hasSize(1);
    }

    @Test
    @DisplayName("choices carry the versions the UI shows and the rules the resolver enforces")
    void choicesCarryTheirRecipe() throws Exception {
        JsonNode choice = optionById(metadata(), "backend").path("choices").get(0);

        assertThat(choice.path("value").asText()).isEqualTo("backend-spring-java");
        assertThat(choice.path("label").asText()).isEqualTo("Spring Boot (Java)");
        assertThat(choice.path("frameworkVersion").asText()).isEqualTo("3.5.5");
        assertThat(texts(choice.path("requires"), null)).contains("build-tool", "database", "project-root");
        // The field kitbash-38 fills; present now so adding it is a server change, not a client one.
        assertThat(choice.path("verification").isNull()).isTrue();
    }

    @Test
    @DisplayName("variables carry their rule and the recipes that need them")
    void variablesAreDescribed() throws Exception {
        JsonNode packageName = null;
        for (JsonNode variable : metadata().path("variables")) {
            if (variable.path("id").asText().equals("packageName")) {
                packageName = variable;
            }
        }

        assertThat(packageName).isNotNull();
        assertThat(packageName.path("pattern").asText()).isNotEmpty();
        assertThat(texts(packageName.path("requiredBy"), null)).contains("backend-spring-java");
    }

    @Test
    @DisplayName("the entity tag is the catalog digest, so a client holding it holds the catalog")
    void etagIsTheCatalogDigest() throws Exception {
        MockHttpServletResponse first =
                mvc.perform(get("/api/v1/metadata")).andReturn().getResponse();
        String etag = first.getHeader("ETag");

        assertThat(etag)
                .contains(json.readTree(first.getContentAsString())
                        .path("catalogDigest")
                        .asText());

        MockHttpServletResponse second = mvc.perform(get("/api/v1/metadata").header("If-None-Match", etag))
                .andReturn()
                .getResponse();

        assertThat(second.getStatus()).isEqualTo(304);
        assertThat(second.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("/actuator/info reports the digest the loader computed")
    void infoReportsTheDigest() throws Exception {
        JsonNode info = json.readTree(
                mvc.perform(get("/actuator/info")).andReturn().getResponse().getContentAsString());

        assertThat(info.path("catalog").path("digest").asText())
                .isEqualTo(metadata().path("catalogDigest").asText());
        assertThat(info.path("catalog").path("recipes").asInt()).isEqualTo(11);
    }

    @Test
    @DisplayName("the OpenAPI document is published, because the client's types are generated from it")
    void publishesItsOwnSpec() throws Exception {
        JsonNode spec = json.readTree(
                mvc.perform(get("/api/v1/openapi")).andReturn().getResponse().getContentAsString());

        assertThat(spec.path("paths").has("/api/v1/metadata")).isTrue();
        assertThat(spec.path("paths").has("/api/v1/validate")).isTrue();
        assertThat(spec.path("paths").has("/api/v1/generate")).isTrue();
    }

    @Test
    @DisplayName("validate resolves without generating, and answers 200 even when the selection is wrong")
    void validateAnswersWithAPayload() throws Exception {
        String body =
                """
                {"schemaVersion":1,"projectName":"svc",
                 "options":{"backend":"backend-spring-java"},
                 "variables":{}}""";

        JsonNode response = post("/api/v1/validate", body);

        // A half-made selection is the wizard's normal state; 400 would make it an error state.
        assertThat(response.path("valid").asBoolean()).isFalse();
        assertThat(response.path("conflicts")).isNotEmpty();
        assertThat(response.path("conflicts").get(0).path("optionId").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("validate reports the resolved stack, including what it added")
    void validateReportsTheStack() throws Exception {
        String body =
                """
                {"schemaVersion":1,"projectName":"svc",
                 "options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts",
                            "database":"db-postgres-flyway"},
                 "variables":{"groupId":"com.acme","packageName":"com.acme.svc","javaVersion":"21",
                              "entityName":"Widget","entityTable":"widgets","envPrefix":"SVC"}}""";

        JsonNode response = post("/api/v1/validate", body);

        assertThat(response.path("valid").asBoolean()).isTrue();
        assertThat(texts(response.path("recipes"), "id")).contains("base", "backend-spring-java");
        // §9: the right rail shows what the resolver added, not just what was picked.
        boolean baseIsImplied = false;
        for (JsonNode recipe : response.path("recipes")) {
            if (recipe.path("id").asText().equals("base")) {
                baseIsImplied = recipe.path("implied").asBoolean();
            }
        }
        assertThat(baseIsImplied).isTrue();
        assertThat(response.path("effectiveOptions").path("architecture").asText())
                .isEqualTo("layered");
    }

    /**
     * §31's error path, asserted against the running endpoint rather than checked by hand.
     *
     * <p>The plan's own example of this case says {@code PATCH_TARGET_MISSING} — auth patches files
     * a backend produces, so with no backend the first patch would fail. It does not get that far,
     * and the reason is an improvement rather than a deviation: {@code feature-auth-jwt} declares
     * {@code requires: [http-server]}, so the resolver refuses the selection before anything is
     * rendered and says which capability is missing <em>and</em> which option would supply it. A
     * message naming a file the user never asked for would be strictly worse.
     */
    @Test
    @DisplayName("auth with no backend is refused at validate time, naming the capability and the option")
    void authNeedsSomethingToProtect() throws Exception {
        String body =
                """
                {"schemaVersion":1,"projectName":"svc",
                 "options":{"frontend":"frontend-react-vite","auth":true},
                 "variables":{"entityName":"Widget","entityTable":"widgets"}}""";

        JsonNode response = post("/api/v1/validate", body);

        assertThat(response.path("valid").asBoolean()).isFalse();

        JsonNode conflict = null;
        for (JsonNode candidate : response.path("conflicts")) {
            if (candidate.path("message").asText().contains("http-server")) {
                conflict = candidate;
            }
        }
        assertThat(conflict)
                .as("no conflict mentioned 'http-server'; the whole list was %s", response.path("conflicts"))
                .isNotNull();
        assertThat(conflict.path("code").asText()).isEqualTo("CAPABILITY_UNSATISFIED");
        assertThat(conflict.path("message").asText()).contains("feature-auth-jwt", "http-server");
        // The hint has to name the way out, which is the option that would provide it.
        assertThat(conflict.path("hint").asText()).contains("backend");
    }

    private JsonNode post(String path, String body) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private static JsonNode optionById(JsonNode document, String id) {
        for (JsonNode group : document.path("groups")) {
            for (JsonNode option : group.path("options")) {
                if (option.path("id").asText().equals(id)) {
                    return option;
                }
            }
        }
        throw new AssertionError("no option '" + id + "' in the metadata document");
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node ->
                values.add(field == null ? node.asText() : node.path(field).asText()));
        return values;
    }
}
