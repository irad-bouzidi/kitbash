package com.example.demo.adapter.inbound.web;

import com.example.demo.config.CorrelationIdFilter;
import com.example.demo.domain.DuplicateWidgetNameException;
import com.example.demo.domain.WidgetNotFoundException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors are RFC 9457 problem documents, and each one carries the request's correlation id so a
 * user can paste the response into a ticket and the log line is one search away.
 *
 * <p>Translating a domain failure into a status code is the web adapter's job. The domain throws
 * {@code WidgetNotFoundException} and has never heard of 404.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WidgetNotFoundException.class)
    public ProblemDetail notFound(WidgetNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Widget not found", exception.getMessage());
    }

    @ExceptionHandler(DuplicateWidgetNameException.class)
    public ProblemDetail duplicate(DuplicateWidgetNameException exception) {
        return problem(HttpStatus.CONFLICT, "Duplicate widget name", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
