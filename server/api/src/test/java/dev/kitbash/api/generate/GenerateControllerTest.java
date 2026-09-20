package dev.kitbash.api.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GenerateControllerTest {

    private static final String VALID =
            """
            {
              "schemaVersion": 1,
              "projectName": "customer-management",
              "options": {
                "buildTool": "build-gradle-kts",
                "backend": "backend-spring-java",
                "database": "db-postgres-flyway",
                "docker": true
              },
              "variables": {
                "groupId": "com.acme",
                "packageName": "com.acme.customer",
                "javaVersion": "21",
                "entityName": "Widget",
                "entityTable": "widgets",
                "envPrefix": "CUSTOMER"
              }
            }""";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("the response is a zip, offered as a download named after the project")
    void respondsWithADownloadableZip() throws Exception {
        MockHttpServletResponse response = generate(VALID);

        assertThat(response.getStatus()).as("%s", response.getContentAsString()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"customer-management.zip\"");
        assertThat(response.getContentAsByteArray()).startsWith('P', 'K');
    }

    @Test
    @DisplayName("the zip unpacks to one project directory containing a buildable tree")
    void zipContainsTheProject() throws Exception {
        List<String> names = entries(generate(VALID).getContentAsByteArray());

        assertThat(names)
                .contains(
                        "customer-management/",
                        "customer-management/gradlew",
                        "customer-management/build.gradle.kts",
                        "customer-management/src/main/java/com/acme/customer/CustomerManagementApplication.java",
                        "customer-management/src/main/resources/db/migration/V1__create_widgets.sql",
                        "customer-management/compose.yaml",
                        "customer-management/.git/HEAD")
                .allSatisfy(name -> assertThat(name).startsWith("customer-management/"));
    }

    @Test
    @DisplayName("a selection that names nothing is a 400, not an empty zip")
    void refusesToGenerateNothing() throws Exception {
        // An unknown option key is carried rather than rejected — the envelope is forward
        // compatible by design (§7) — but a selection with nothing in it selects no recipes, and
        // an empty zip looks like the generator worked.
        String body =
                """
                {"schemaVersion":1,"projectName":"my-service","options":{"somethingNew":"yes"},"variables":{}}""";

        MockHttpServletResponse response = generate(body);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("does not name anything to generate")
                .contains("/api/v1/metadata");
    }

    @Test
    @DisplayName("a mistyped recipe id is a 400 that suggests the one that was meant")
    void rejectsUnknownRecipes() throws Exception {
        String body =
                """
                {"schemaVersion":1,"projectName":"my-service","options":{"backend":"backend-spring-jva"},"variables":{}}""";

        MockHttpServletResponse response = generate(body);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("backend-spring-java");
    }

    @Test
    @DisplayName("a missing required variable is a 400 naming the variable and the recipe that needs it")
    void rejectsMissingVariables() throws Exception {
        String body =
                """
                {"schemaVersion":1,"projectName":"my-service",
                 "options":{"buildTool":"build-gradle-kts","backend":"backend-spring-java",
                            "database":"db-postgres-flyway"},
                 "variables":{"groupId":"com.acme"}}""";

        MockHttpServletResponse response = generate(body);

        assertThat(response.getStatus()).isEqualTo(400);
        // Which variable is named depends on recipe order, and pinning that would make this a
        // test of the sort order. What matters is that it names one, and the recipe that wants it.
        assertThat(response.getContentAsString())
                .contains("\"error\":\"INVALID_IDENTIFIER\"")
                .contains("not supplied")
                .contains("required by");
    }

    @Test
    @DisplayName("the response carries the catalog digest, which is what makes a bug report actionable")
    void reportsTheCatalogDigest() throws Exception {
        MockHttpServletResponse response = generate(VALID);

        assertThat(response.getHeader("X-Kitbash-Catalog-Digest")).startsWith("sha256:");
        assertThat(response.getHeader("X-Kitbash-Selection-Hash")).hasSize(64);
    }

    @Test
    @DisplayName("a bad project name is a 400 problem document naming the field, with no body written")
    void rejectsBadProjectName() throws Exception {
        String body =
                """
                {"schemaVersion":1,"projectName":"Not A Project","options":{},"variables":{}}""";

        MockHttpServletResponse response = generate(body);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("\"field\":\"projectName\"")
                .contains("Invalid selection");
    }

    @Test
    @DisplayName("a form submission downloads the same zip, so the browser can navigate to it")
    void acceptsFormSubmission() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("selection", VALID))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"customer-management.zip\"");
        assertThat(response.getContentAsByteArray()).isEqualTo(generate(VALID).getContentAsByteArray());
    }

    @Test
    @DisplayName("a form field that is not an envelope is a 400 naming the field")
    void rejectsMalformedFormField() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("selection", "not json"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"field\":\"selection\"");
    }

    @Test
    @DisplayName("a malformed body is a 400, not a 500")
    void rejectsMalformedBody() throws Exception {
        assertThat(generate("not json").getStatus()).isEqualTo(400);
    }

    private MockHttpServletResponse generate(String body) throws Exception {
        return mvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    private static List<String> entries(byte[] archive) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
