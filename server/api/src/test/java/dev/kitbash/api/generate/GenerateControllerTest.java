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
              "options": { "backend": "backend-spring-boot-java", "docker": true },
              "variables": { "groupId": "com.acme", "packageName": "com.acme.customer", "javaVersion": "21" }
            }""";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("the response is a zip, offered as a download named after the project")
    void respondsWithADownloadableZip() throws Exception {
        MockHttpServletResponse response = generate(VALID);

        assertThat(response.getStatus()).isEqualTo(200);
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
                        "customer-management/.git/HEAD")
                .allSatisfy(name -> assertThat(name).startsWith("customer-management/"));
    }

    @Test
    @DisplayName("an unknown option key is accepted and ignored in this phase")
    void ignoresUnknownOptions() throws Exception {
        String body =
                """
                {"schemaVersion":1,"projectName":"my-service","options":{"somethingNew":"yes"},"variables":{}}""";

        assertThat(generate(body).getStatus()).isEqualTo(200);
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
