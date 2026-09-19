package dev.kitbash.api.generate;

import dev.kitbash.core.pack.DeterministicZipWriter;
import dev.kitbash.core.selection.Identifiers;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.Workspace;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GenerateController {

    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);

    private final Phase0ProjectGenerator generator;

    public GenerateController(Phase0ProjectGenerator generator) {
        this.generator = generator;
    }

    /**
     * Streams the generated project as a zip.
     *
     * <p>Synchronous by design, and it stays that way (§8): rendering takes tens of milliseconds, so
     * a 202 and a queue would be complexity with no payoff. The one 202 in this system is build
     * validation, which genuinely takes minutes.
     */
    @PostMapping(value = "/generate", produces = "application/zip")
    public void generate(@RequestBody GenerateRequest request, HttpServletResponse response) throws IOException {
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
