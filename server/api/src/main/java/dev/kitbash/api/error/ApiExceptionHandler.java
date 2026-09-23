package dev.kitbash.api.error;

import dev.kitbash.api.security.RateLimitExceededException;
import dev.kitbash.api.verify.AlreadyVerifyingException;
import dev.kitbash.api.verify.QueueFullException;
import dev.kitbash.api.verify.UnknownVerificationException;
import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.selection.SelectionValidationException;
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

    @ExceptionHandler(GenerationException.class)
    public ProblemDetail generationFailed(GenerationException exception) {
        GenerationError error = exception.error();
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

    @ExceptionHandler(SelectionValidationException.class)
    public ProblemDetail invalidSelection(SelectionValidationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid selection");
        problem.setProperty("field", exception.field());
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
