package dev.kitbash.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The web client's types are generated from a checked-in copy of the OpenAPI document (§9), and a
 * checked-in copy of anything drifts. This is what stops it.
 *
 * <p>Checked in rather than fetched at build time so that {@code pnpm build} needs no running
 * server — CI builds the web app in a job that has no JVM in it — and so that a change to the API's
 * shape shows up as a reviewable diff rather than as a silent retype on somebody's machine.
 *
 * <p>Regenerate with {@code ./gradlew :api:test -Dkitbash.openapi.update=true}, then
 * {@code pnpm gen:api} in {@code web/}. Two steps, deliberately: the spec diff and the type diff
 * are both worth looking at.
 *
 * <p>It boots <b>with</b> persistence, which costs a container and buys the only thing that
 * matters here: the document has to describe the API as deployed. The preset endpoints exist only
 * when there is a database (§10, §12), so a document generated without one would quietly omit
 * them — and the web client's types are generated from this document, so the omission would
 * surface as a page that cannot be written rather than as a failing test.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "test"})
// Signed in, because since kitbash-22 every endpoint but /actuator/health is (§13). These tests
// are about what the endpoints say, not about who may call them — SecurityTest covers that.
@WithMockUser
@AutoConfigureMockMvc
class OpenApiSpecDriftTest {

    private static final boolean UPDATE = Boolean.getBoolean("kitbash.openapi.update");

    private static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    @org.springframework.test.context.DynamicPropertySource
    static void datasource(org.springframework.test.context.DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("the checked-in OpenAPI document is the one this server serves")
    void specIsInStep() throws Exception {
        String live = json.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(json.readTree(mvc.perform(get("/api/v1/openapi"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString()))
                + System.lineSeparator();

        Path checkedIn = repositoryRoot().resolve("web/src/lib/api/openapi.json");
        if (UPDATE) {
            write(checkedIn, live);
            return;
        }

        assertThat(read(checkedIn))
                .describedAs("web/src/lib/api/openapi.json is out of step with the server. Regenerate it with "
                        + "`./gradlew :api:test -Dkitbash.openapi.update=true` and then `pnpm gen:api` in web/.")
                .isEqualTo(live);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("recipes"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate).as("repository root").isNotNull();
        return candidate;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }
    }
}
