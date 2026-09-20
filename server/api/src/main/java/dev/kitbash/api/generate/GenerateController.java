package dev.kitbash.api.generate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.api.security.Caller;
import dev.kitbash.api.security.RateLimiter;
import dev.kitbash.core.hash.Sha256;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.core.selection.SelectionValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;
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
    private final RateLimiter limiter;
    private final ZipCache cache;

    public GenerateController(GenerationPipeline pipeline, ObjectMapper json, RateLimiter limiter, ZipCache cache) {
        this.pipeline = pipeline;
        this.json = json;
        this.limiter = limiter;
        this.cache = cache;
    }

    /**
     * Streams the generated project as a zip.
     *
     * <p>Synchronous by design, and it stays that way (§8): rendering takes tens of milliseconds, so
     * a 202 and a queue would be complexity with no payoff. The one 202 in this system is build
     * validation, which genuinely takes minutes.
     */
    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public void generate(
            @RequestBody GenerateRequest request, HttpServletRequest httpRequest, HttpServletResponse response)
            throws IOException {
        stream(request, httpRequest, response);
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
    public void generateFromForm(
            @RequestParam("selection") String selection, HttpServletRequest httpRequest, HttpServletResponse response)
            throws IOException {
        GenerateRequest request;
        try {
            request = json.readValue(selection, GenerateRequest.class);
        } catch (JsonProcessingException e) {
            throw new SelectionValidationException("selection", "The 'selection' field is not a valid envelope.");
        }
        stream(request, httpRequest, response);
    }

    private void stream(GenerateRequest request, HttpServletRequest httpRequest, HttpServletResponse response)
            throws IOException {
        long start = System.nanoTime();
        SelectionEnvelope envelope = request.toEnvelope();

        // Stage 1 first, on its own. Parsing is pure and cheap, it rejects a hostile or malformed
        // selection before anything is spent on it (§13, kitbash-20), and it is what produces the
        // hash the cache is keyed on.
        Selection selection = pipeline.parse(envelope);
        String cacheKey = cacheKey(selection);

        Optional<byte[]> cached = cache.find(cacheKey);
        if (cached.isPresent()) {
            // §13: a cache hit consumes no budget. The limiter is below this return on purpose —
            // regenerating the house stack over and over is the usage this product most wants to
            // encourage, and serving bytes that already exist costs nothing worth metering. This
            // is also why the limiter is not a servlet filter: a filter would have charged for
            // this request before the handler ever got the chance to find them.
            writeZip(response, selection.projectName(), pipeline.catalog().digest(), selection.hash());
            try (OutputStream out = response.getOutputStream()) {
                out.write(cached.get());
            }
            log.info("Served selection={} from cache bytes={}", selection.hash(), cached.get().length);
            return;
        }

        limiter.require(RateLimiter.Bucket.GENERATE, Caller.key(httpRequest));

        // The whole pipeline runs before a single header is written: once the body starts
        // streaming there is no way to turn the response into a 400, and every failure worth
        // reporting — an unknown recipe, a conflict, a breached cap — happens before packaging.
        GeneratedProject project = pipeline.generate(envelope);

        writeZip(response, project.projectName(), project.lock().catalogDigest(), project.selectionHash());

        if (cache.stores()) {
            // Held whole only when something is going to keep it. The zip is a few hundred
            // kilobytes (§2 keeps the workspace in memory anyway), so this is one copy, not a
            // second rendering.
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            project.streamTo(buffer);
            byte[] zip = buffer.toByteArray();
            cache.put(cacheKey, zip);
            try (OutputStream out = response.getOutputStream()) {
                out.write(zip);
            }
        } else {
            try (OutputStream out = response.getOutputStream()) {
                project.streamTo(out);
            }
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

    /** §10's key: the catalog and the canonical selection together, since either changes the bytes. */
    private String cacheKey(Selection selection) {
        return Sha256.ofUtf8(pipeline.catalog().digest() + "\u0000" + selection.hash());
    }

    private static void writeZip(
            HttpServletResponse response, String projectName, String catalogDigest, String selectionHash) {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + projectName + ".zip\"");
        // The zip is deterministic but the endpoint is not idempotent for caches to guess at.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        // The digest that makes a bug report actionable (§9), and the hash the cache keys on.
        response.setHeader("X-Kitbash-Catalog-Digest", catalogDigest);
        response.setHeader("X-Kitbash-Selection-Hash", selectionHash);
    }
}
