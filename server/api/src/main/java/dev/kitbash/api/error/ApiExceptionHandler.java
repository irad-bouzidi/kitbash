package dev.kitbash.api.error;

import dev.kitbash.core.error.ErrorCode;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.selection.SelectionValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
        return problem;
    }

    @ExceptionHandler(SelectionValidationException.class)
    public ProblemDetail invalidSelection(SelectionValidationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid selection");
        problem.setProperty("field", exception.field());
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
