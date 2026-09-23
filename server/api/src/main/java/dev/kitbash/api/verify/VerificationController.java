package dev.kitbash.api.verify;

import dev.kitbash.api.generate.GenerateRequest;
import dev.kitbash.api.security.Caller;
import dev.kitbash.api.store.VerificationRun;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.selection.Selection;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/verify} and {@code GET /api/v1/verify/{id}} — "does my combination actually
 * build?" (§8, §12, §18).
 *
 * <p>This is the <b>one</b> place in kitbash where the 202 pattern exists, and §8 is explicit about
 * why: generation is synchronous and streamed because it takes tens of milliseconds, while building
 * a generated project genuinely takes minutes. A job is a cost — a second endpoint, a status model,
 * a client that has to poll — and it is worth paying exactly once, here.
 *
 * <p>So the status code carries the meaning. <b>202</b> is "a container is starting for you";
 * <b>200</b> is "this was already answered", which is the common case and costs nothing, because
 * the nightly matrix has already built every enumerated combination and somebody else has probably
 * asked about yours.
 */
@RestController
@RequestMapping("/api/v1")
// A run is a row, so there is no verification without a database — the same rule presets and
// history follow. A deployment without one still generates projects; it simply cannot remember
// that it verified anything, and an endpoint that forgets its answers is the dedupe's opposite.
@Profile("persistence")
public class VerificationController {

    private final GenerationPipeline pipeline;
    private final VerificationService verifications;

    public VerificationController(GenerationPipeline pipeline, VerificationService verifications) {
        this.pipeline = pipeline;
        this.verifications = verifications;
    }

    /**
     * Ask for a verification, or be handed the one that already answers the question.
     *
     * <p>The selection is resolved first. A selection that cannot even be resolved cannot be
     * generated, let alone built, and spending a container to discover that would be spending
     * minutes on something {@code /validate} answers in milliseconds — so it is a 400 here, through
     * the same pipeline and the same §14 problem shapes as everywhere else.
     */
    @PostMapping("/verify")
    public ResponseEntity<VerificationResponse> verify(@RequestBody GenerateRequest request) {
        Resolution resolution = pipeline.validate(request.toEnvelope());
        if (!resolution.valid()) {
            // The same error the generator would have raised, raised before a container was spent
            // discovering it — and through the same §14 problem shape, so a client that can render
            // a conflict from /generate can render this one.
            throw new GenerationException(resolution.firstConflict());
        }
        if (resolution.recipes().isEmpty()) {
            throw GenerationError.emptySelection().asException();
        }

        Selection selection = pipeline.parse(request.toEnvelope());
        UUID owner = Caller.ownerId()
                .orElseThrow(() ->
                        new IllegalStateException("An unauthenticated request reached /verify, which the security "
                                + "configuration should have refused."));

        VerificationService.Claim claim = verifications.verify(
                selection, resolution.lock(pipeline.catalog().digest()), owner);
        // An answer that already exists comes back complete, log and all. The point of the fast
        // path is that the second person to ask the question is finished rather than pointed at a
        // second request, and a 200 whose body said only "passed" would make them poll for
        // something already on the disk.
        VerificationResponse body = VerificationResponse.of(
                claim.run(),
                claim.started() ? null : verifications.logOf(claim.run()).orElse(null));

        return ResponseEntity.status(claim.started() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/verify/" + claim.run().id()))
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * Where a run got to, with its log once there is one.
     *
     * <p>The log is served through the API rather than as a link into the bucket (§10). A signed
     * URL would be a second door with its own expiry and its own idea of who may read a run, and
     * the question of who may read a run belongs to the code that knows what a run is.
     */
    @GetMapping("/verify/{id}")
    public ResponseEntity<VerificationResponse> status(@PathVariable UUID id) {
        VerificationRun run = verifications.find(id).orElseThrow(() -> new UnknownVerificationException(id));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(VerificationResponse.of(run, verifications.logOf(run).orElse(null)));
    }

    /**
     * What a caller sees of a run.
     *
     * <p>{@code selectionHash} and {@code catalogDigest} are both here because together they are
     * the <em>question</em> the run answers. A result read a week later against a catalog that has
     * since moved is a result about a different catalog, and a client that cannot see the digest
     * has no way to know that.
     */
    public record VerificationResponse(
            UUID id,
            String status,
            String selectionHash,
            String catalogDigest,
            Instant startedAt,
            Instant finishedAt,
            String log) {

        static VerificationResponse of(VerificationRun run, String log) {
            return new VerificationResponse(
                    run.id(),
                    run.status().wireName(),
                    run.selectionHash(),
                    run.catalogDigest(),
                    run.startedAt(),
                    run.finishedAt(),
                    log);
        }
    }
}
