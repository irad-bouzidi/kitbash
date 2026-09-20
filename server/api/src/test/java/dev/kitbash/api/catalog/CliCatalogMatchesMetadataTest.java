package dev.kitbash.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.catalog.metadata.MetadataAssembler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code kitbash catalog --json} and {@code GET /api/v1/metadata} describe the same catalog, and
 * this is the cheapest possible guard that they keep doing so.
 *
 * <p>The drift it prevents is not hypothetical: a CLI that describes options the wizard does not
 * offer is a CLI somebody scripts against and then cannot reproduce in the UI. Both sides call
 * {@link MetadataAssembler}, so the assertion is really that neither has grown a second path — and
 * it lives in {@code api} because that is the module that can see both.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CliCatalogMatchesMetadataTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("the CLI's catalog document is the API's, node for node")
    void theTwoSurfacesAgree() throws Exception {
        CatalogLoader.LoadedCatalog loaded =
                new CatalogLoader().loadAll(repositoryRoot().resolve("recipes"));

        JsonNode fromCli = json.valueToTree(MetadataAssembler.assemble(loaded.catalog()));
        JsonNode fromApi = json.readTree(
                mvc.perform(get("/api/v1/metadata")).andReturn().getResponse().getContentAsString());

        assertThat(fromCli).isEqualTo(fromApi);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("recipes"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate).as("repository root").isNotNull();
        return candidate;
    }
}
