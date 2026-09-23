package dev.kitbash.api.error;

import dev.kitbash.api.security.RateLimitExceededException;
import dev.kitbash.api.verify.AlreadyVerifyingException;
import dev.kitbash.api.verify.QueueFullException;
import dev.kitbash.api.verify.UnknownVerificationCellException;
import dev.kitbash.api.verify.UnknownVerificationException;
import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.GenerationException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A rejected selection is a problem document naming the field, never a stack trace.
 *
 * <p>The §14 envelope is rendered verbatim: the typed error already carries the code, the stage,
 * the recipe, the file, the message and the hint, so this class copies them onto a {@code
 * ProblemDetail} and adds nothing of its own. Assembling prose here instead would put the error
 * vocabulary in two places, and §14 is explicit that it belongs in {@code core}.
 *
 * <p>The full structured surface — machine-readable codes in the wizard, error-rate metrics by
 * type — is {@code kitbash-39}. This is the shape it extends.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final RejectionMetrics rejections;

    public ApiExceptionHandler(RejectionMetrics rejections) {
        this.rejections = rejections;
    }

    @ExceptionHandler(GenerationException.class)
    public ProblemDetail generationFailed(GenerationException exception) {
        GenerationError error = exception.error();
        // Counted here rather than at the throw sites: this is the one place every typed rejection
        // passes through, and counting at fourteen sites is fourteen chances to forget.
        rejections.record(error);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusFor(error.code()), error.message());
        problem.setTitle(title(error.code()));
        problem.setProperty("error", error.code().name());
        problem.setProperty("stage", error.stage().wireName());
        problem.setProperty("hint", error.hint());
        if (error.recipe() != null) {
            problem.setProperty("recipe", error.recipe());
        }
        if (error.file() != null) {
            problem.setProperty("file", error.file());
        }
        if (error.selectionHash() != null) {
            problem.setProperty("selectionHash", error.selectionHash());
        }
        // Copied, not composed: the wizard highlights the field it was told about, and kitbash-39
        // counts rejections by rule. Both want these as properties rather than as prose to parse.
        if (error instanceof GenerationError.InvalidIdentifier invalid) {
            problem.setProperty("field", invalid.field());
            problem.setProperty("rule", invalid.rule());
        }
        return problem;
    }

    /**
     * A resource that is not there (§14, kitbash-39).
     *
     * <p>These three — a preset, a share link, a generation — used to answer <b>400 with no code
     * and no hint</b>, through an exception meant for a malformed selection. Both halves were
     * wrong: the caller had sent a perfectly good id for a row that no longer exists, and the
     * envelope carried none of what §14 requires. A 404 with the envelope is what they are.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail notFound(ResourceNotFoundException exception) {
        rejections.recordUntyped("NOT_FOUND", null);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Not found");
        problem.setProperty("error", "NOT_FOUND");
        problem.setProperty("resource", exception.resource());
        problem.setProperty("hint", exception.hint());
        return problem;
    }

    /**
     * The budget is spent (§13, §14).
     *
     * <p>A 429 with no body is a client that retries immediately and fails again. This one carries
     * the envelope and a {@code Retry-After}, so the wizard can say when rather than whether.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException exception) {
        long seconds = exception.retryAfterSeconds();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests in a short time. This limit is per person, and it is modest "
                        + "rather than metered.");
        problem.setTitle("Slow down");
        problem.setProperty("error", "RATE_LIMITED");
        problem.setProperty("retryAfterSeconds", seconds);
        // The hint is per bucket. "A cache hit costs nothing" is true of generation and meaningless
        // on a share link, and a hint that explains the wrong endpoint is worse than no hint at all.
        problem.setProperty(
                "hint",
                "Wait %d second%s and try again. ".formatted(seconds, seconds == 1 ? "" : "s")
                        + switch (exception.bucket()) {
                            case GENERATE ->
                                "A repeat of a generation already served from cache costs "
                                        + "nothing, so identical downloads are not what ran this out.";
                            case SHARE ->
                                "A configuration can also be shared as a URL, which needs no "
                                        + "link minted and never expires.";
                        });

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(problem);
    }

    /**
     * The verification pool is busy (§12, §13, §14).
     *
     * <p>Two refusals, one status, two genuinely different next actions — which is why they are two
     * exceptions rather than one with a flag. "Wait for the run you already have" and "come back
     * when the queue is shorter" send a caller to different places, and a 429 that did not
     * distinguish them would send both to the wrong one.
     */
    @ExceptionHandler(QueueFullException.class)
    public ResponseEntity<ProblemDetail> queueFull(QueueFullException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Every verification worker is busy and the queue is full. Nothing was queued, "
                        + "because being queued for an hour without being told is worse than being "
                        + "refused now.");
        problem.setTitle("The verification queue is full");
        problem.setProperty("error", "VERIFY_QUEUE_FULL");
        problem.setProperty("queueDepth", exception.depth());
        problem.setProperty(
                "hint",
                "There are %d run%s waiting. Try again in a few minutes — or check whether the "
                                .formatted(exception.depth(), exception.depth() == 1 ? "" : "s")
                        + "nightly matrix has already built this combination, in which case asking "
                        + "again will be answered instantly rather than queued.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .body(problem);
    }

    @ExceptionHandler(AlreadyVerifyingException.class)
    public ResponseEntity<ProblemDetail> alreadyVerifying(AlreadyVerifyingException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "One verification at a time per person. A build takes minutes of a container, and "
                        + "the limit is what keeps the pool open to everybody without a quota.");
        problem.setTitle("A verification is already running for you");
        problem.setProperty("error", "VERIFY_ALREADY_RUNNING");
        problem.setProperty("runId", exception.inFlight().toString());
        problem.setProperty(
                "hint", "Poll /api/v1/verify/" + exception.inFlight() + " — that run is yours and is still going.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(problem);
    }

    /** A cell whose log the published run does not carry (§14). */
    @ExceptionHandler(UnknownVerificationCellException.class)
    public ProblemDetail unknownVerificationCell(UnknownVerificationCellException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "The currently published verification run has no log for that cell. It may have "
                        + "been replaced by a newer run.");
        problem.setTitle("No log for that cell");
        problem.setProperty("error", "VERIFY_CELL_NOT_FOUND");
        problem.setProperty("cell", exception.cellId());
        return problem;
    }

    /** An id for a run that does not exist (§14). */
    @ExceptionHandler(UnknownVerificationException.class)
    public ProblemDetail unknownVerification(UnknownVerificationException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "There is no verification run with that id.");
        problem.setTitle("Unknown verification run");
        problem.setProperty("error", "VERIFY_NOT_FOUND");
        problem.setProperty("runId", exception.id().toString());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableBody(HttpMessageNotReadableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request body is not a readable selection envelope.");
        problem.setTitle("Malformed request");
        return problem;
    }

    /**
     * Anything with no mapping: a 500 in the §14 shape, and a logged defect.
     *
     * <p>§39 allows a generic fallback for <em>genuinely unmapped</em> failures and requires those
     * to be logged as defects. Both halves matter. Without the handler, Spring's default reply
     * carries {@code include-message: always} — so an {@code IllegalStateException} from inside the
     * patch stage would reach a user as its own text, which is the stack-trace leak §39 forbids by
     * a slower route.
     *
     * <p>Without the log, the fallback would be a place failures go to be forgotten. Every hit here
     * is a throw site somebody has not given an error type to yet, which is a defect in this
     * codebase and not a mistake by the caller — so it is logged at error with the exception, and
     * the reply says as much rather than implying the request was wrong.
     *
     * <p>The reference is a correlation id, not a hash of anything: §39 keeps the envelope exactly
     * as §14 defines it, and an id that lets somebody find the log line belongs beside the envelope
     * rather than inside it.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unmapped(Exception exception) {
        String reference = UUID.randomUUID().toString().substring(0, 8);
        log.error(
                "Unmapped failure {} — this is a defect: the throw site needs a GenerationError variant (§39)",
                reference,
                exception);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something failed inside the generator rather than in the request. The failure has "
                        + "been logged as a defect under reference " + reference + ".");
        problem.setTitle("Unexpected failure");
        problem.setProperty("error", "UNEXPECTED");
        problem.setProperty("reference", reference);
        problem.setProperty(
                "hint",
                "Nothing about the selection needs changing. Quote reference " + reference
                        + " in a bug report — it is in the server log beside the cause.");
        return problem;
    }

    /**
     * Everything the caller can fix is a 400. The two that are not — a template that will not
     * render, a cycle in the catalog's own manifests — are the server's fault however the request
     * was phrased, and reporting them as the caller's mistake sends somebody looking in the wrong
     * place.
     */
    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case UNKNOWN_RECIPE,
                    CAPABILITY_UNSATISFIED,
                    CONFLICT,
                    INVALID_IDENTIFIER,
                    PATCH_TARGET_MISSING,
                    PATCH_COLLISION,
                    LIMIT_EXCEEDED -> HttpStatus.BAD_REQUEST;
            case CYCLE, PATH_ESCAPE, RENDER_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String title(ErrorCode code) {
        return switch (code) {
            case UNKNOWN_RECIPE -> "Unknown recipe";
            case CAPABILITY_UNSATISFIED -> "Incomplete selection";
            case CONFLICT -> "Conflicting selection";
            case CYCLE -> "Catalog cycle";
            case PATCH_TARGET_MISSING -> "Patch target missing";
            case PATCH_COLLISION -> "Patch collision";
            case INVALID_IDENTIFIER -> "Invalid selection";
            case PATH_ESCAPE -> "Unsafe path";
            case LIMIT_EXCEEDED -> "Selection too large";
            case RENDER_FAILED -> "Template failed";
        };
    }
}
