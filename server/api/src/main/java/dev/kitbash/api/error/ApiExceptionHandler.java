package dev.kitbash.api.error;

import dev.kitbash.core.selection.SelectionValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A rejected selection is a problem document naming the field, not a stack trace. The structured
 * error surface proper is kitbash-39; this is the shape it will extend.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

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
}
