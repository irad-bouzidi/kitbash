package dev.kitbash.api.generate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.api.history.GenerationRecorder;
import dev.kitbash.api.security.Caller;
import dev.kitbash.api.security.RateLimiter;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.hash.Sha256;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionEnvelope;
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
    private final GenerationRecorder history;
    private final PopularSelections popular;

    public GenerateController(
            GenerationPipeline pipeline,
            ObjectMapper json,
            RateLimiter limiter,
            ZipCache cache,
            GenerationRecorder history,
            PopularSelections popular) {
        this.pipeline = pipeline;
        this.json = json;
        this.limiter = limiter;
        this.cache = cache;
        this.history = history;
        this.popular = popular;
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
            throw GenerationError.invalidIdentifier(
                            "selection",
                            "the form field",
                            "is not a readable selection envelope",
                            "Send the JSON the wizard produces: an object with projectName, options "
                                    + "and variables. POST /api/v1/validate will say what is wrong with it.")
                    .asException();
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
        //
        // It is also the commonest place to fail — a mistyped recipe id never gets further — so
        // the recording of failures has to start here rather than around the render (§24).
        Selection selection;
        try {
            selection = pipeline.parse(envelope);
        } catch (GenerationException failure) {
            history.failed(
                    envelope,
                    null,
                    Caller.ownerId().orElse(null),
                    failure.error().code().name(),
                    elapsed(start));
            throw failure;
        }
        String cacheKey = cacheKey(selection);
        // Counted whether or not it is served from cache: kitbash-40 wants what people generate,
        // and a popular selection is popular precisely because it keeps being asked for.
        popular.record(selection.hash());

        Optional<byte[]> cached = cache.find(cacheKey);
        if (cached.isPresent()) {
            // §13: a cache hit consumes no budget. The limiter is below this return on purpose —
            // regenerating the house stack over and over is the usage this product most wants to
            // encourage, and serving bytes that already exist costs nothing worth metering. This
            // is also why the limiter is not a servlet filter: a filter would have charged for
            // this request before the handler ever got the chance to find them.
            byte[] zip = cached.get();
            writeZip(response, selection.projectName(), pipeline.catalog().digest(), selection.hash());
            try (OutputStream out = response.getOutputStream()) {
                out.write(zip);
            }
            log.info("Served selection={} from cache bytes={}", selection.hash(), zip.length);

            // Still a row (§27): what somebody downloaded is worth recording whether or not it
            // cost anything to produce, and it points at the artifact that served it rather than
            // at one this request would have made.
            history.servedFromCache(selection, Caller.ownerId().orElse(null), cacheKey, zip.length, elapsed(start));
            return;
        }

        limiter.require(RateLimiter.Bucket.GENERATE, Caller.key(httpRequest));

        // The whole pipeline runs before a single header is written: once the body starts
        // streaming there is no way to turn the response into a 400, and every failure worth
        // reporting — an unknown recipe, a conflict, a breached cap — happens before packaging.
        GeneratedProject project;
        try {
            project = pipeline.generate(envelope);
        } catch (GenerationException failure) {
            // §24: a history that only contains successes cannot answer "why did this break",
            // which is the question people actually bring to a history page.
            history.failed(
                    envelope,
                    selection.hash(),
                    Caller.ownerId().orElse(null),
                    failure.error().code().name(),
                    elapsed(start));
            throw failure;
        }

        writeZip(response, project.projectName(), project.lock().catalogDigest(), project.selectionHash());

        long zipBytes;
        if (cache.stores()) {
            // Held whole only when something is going to keep it. The zip is a few hundred
            // kilobytes (§2 keeps the workspace in memory anyway), so this is one copy, not a
            // second rendering.
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            project.streamTo(buffer);
            byte[] zip = buffer.toByteArray();
            cache.put(cacheKey, zip);
            zipBytes = zip.length;
            try (OutputStream out = response.getOutputStream()) {
                out.write(zip);
            }
        } else {
            // Counted as it goes past, per §24: the size worth recording is the size of what the
            // user received, and the only place that number exists is the stream.
            try (CountingStream out = new CountingStream(response.getOutputStream())) {
                project.streamTo(out);
                zipBytes = out.count();
            }
        }

        // Hashes and recipe ids only: §10 keeps project and package names out of the logs.
        log.info(
                "Generated selection={} recipes=[{}] files={} bytes={} in {}ms",
                project.selectionHash(),
                project.lock().coordinates(),
                project.fileCount(),
                project.totalBytes(),
                elapsed(start).toMillis());

        // Written after the response, not before it: §24 requires recording not to slow the
        // stream, and the two numbers worth keeping — how long and how large — are only known
        // once it is over.
        history.succeeded(
                selection,
                project.lock(),
                Caller.ownerId().orElse(null),
                project.projectName(),
                // Only when something kept the bytes: a row pointing at an object that was never
                // written would promise a download it cannot serve.
                cache.stores() ? cacheKey : null,
                zipBytes,
                elapsed(start));
    }

    private static java.time.Duration elapsed(long startNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startNanos);
    }

    /**
     * Counts bytes on their way out.
     *
     * <p>§24 asks for the recorded size to come from the stream rather than from the workspace,
     * and the two differ by however well the project compressed — a history row saying 151 KB for
     * a 156 KB download would be a number nobody could reconcile with what they downloaded.
     */
    private static final class CountingStream extends java.io.FilterOutputStream {

        private long count;

        CountingStream(OutputStream delegate) {
            super(delegate);
        }

        @Override
        public void write(int singleByte) throws IOException {
            out.write(singleByte);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            // FilterOutputStream's own implementation writes a byte at a time; forwarding the
            // whole array is the difference between a copy and a crawl.
            out.write(bytes, offset, length);
            count += length;
        }

        long count() {
            return count;
        }
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
