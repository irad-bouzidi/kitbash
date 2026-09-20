package dev.kitbash.api.generate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.selection.SelectionValidationException;
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

    private final GenerationPipeline pipeline;
    private final ObjectMapper json;

    public GenerateController(GenerationPipeline pipeline, ObjectMapper json) {
        this.pipeline = pipeline;
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
        long start = System.nanoTime();

        // The whole pipeline runs before a single header is written: once the body starts
        // streaming there is no way to turn the response into a 400, and every failure worth
        // reporting — an unknown recipe, a conflict, a breached cap — happens before packaging.
        GeneratedProject project = pipeline.generate(request.toEnvelope());

        response.setContentType("application/zip");
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + project.projectName() + ".zip\"");
        // The zip is deterministic but the endpoint is not idempotent for caches to guess at.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        // The digest that makes a bug report actionable (§9), and the hash the cache will key on.
        response.setHeader("X-Kitbash-Catalog-Digest", project.lock().catalogDigest());
        response.setHeader("X-Kitbash-Selection-Hash", project.selectionHash());

        try (OutputStream out = response.getOutputStream()) {
            project.streamTo(out);
        }

        // Hashes and recipe ids only: §10 keeps project and package names out of the logs.
        log.info(
                "Generated selection={} recipes=[{}] files={} bytes={} in {}ms",
                project.selectionHash(),
                project.lock().coordinates(),
                project.fileCount(),
                project.totalBytes(),
                (System.nanoTime() - start) / 1_000_000);
    }
}
