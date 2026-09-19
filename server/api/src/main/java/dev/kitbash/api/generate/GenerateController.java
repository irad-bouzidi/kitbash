package dev.kitbash.api.generate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.core.pack.DeterministicZipWriter;
import dev.kitbash.core.selection.Identifiers;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionValidationException;
import dev.kitbash.core.workspace.Workspace;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GenerateController {

    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);

    private final Phase0ProjectGenerator generator;
    private final ObjectMapper json;

    public GenerateController(Phase0ProjectGenerator generator, ObjectMapper json) {
        this.generator = generator;
        this.json = json;
    }

    /**
     * Streams the generated project as a zip.
     *
     * <p>Synchronous by design, and it stays that way (§8): rendering takes tens of milliseconds, so
     * a 202 and a queue would be complexity with no payoff. The one 202 in this system is build
     * validation, which genuinely takes minutes.
     */
    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public void generate(@RequestBody GenerateRequest request, HttpServletResponse response) throws IOException {
        stream(request, response);
    }

    /**
     * The same endpoint, submitted by an ordinary HTML form.
     *
     * <p>A browser cannot post JSON through a real form, and the download has to be a navigation
     * rather than a fetch-and-blob so the user gets native download progress (§9). So the envelope
     * arrives as one form field holding the same JSON: the shape clients send is unchanged, and the
     * web app does not have to hold a multi-megabyte string in the tab to offer a file.
     */
    @PostMapping(
            value = "/generate",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = "application/zip")
    public void generateFromForm(@RequestParam("selection") String selection, HttpServletResponse response)
            throws IOException {
        GenerateRequest request;
        try {
            request = json.readValue(selection, GenerateRequest.class);
        } catch (JsonProcessingException e) {
            throw new SelectionValidationException("selection", "The 'selection' field is not a valid envelope.");
        }
        stream(request, response);
    }

    private void stream(GenerateRequest request, HttpServletResponse response) throws IOException {
        Selection selection = request.toSelection();
        // Validated before a single header is written: once the body starts streaming there is no
        // way to turn the response into a 400.
        String projectName = Identifiers.requireProjectName(selection.projectName());

        Workspace workspace = generator.generate(selection);
        long start = System.nanoTime();

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + projectName + ".zip\"");
        // The zip is deterministic but the endpoint is not idempotent for caches to guess at.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        try (OutputStream out = response.getOutputStream()) {
            DeterministicZipWriter.write(workspace, projectName, out);
        }

        log.info(
                "Generated project name={} files={} bytes={} in {}ms",
                projectName,
                workspace.fileCount(),
                workspace.totalBytes(),
                (System.nanoTime() - start) / 1_000_000);
    }
}
