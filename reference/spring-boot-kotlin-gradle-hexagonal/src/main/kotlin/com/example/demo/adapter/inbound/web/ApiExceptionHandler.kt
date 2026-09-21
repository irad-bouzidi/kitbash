package com.example.demo.adapter.inbound.web

import com.example.demo.config.CorrelationIdFilter
import com.example.demo.domain.DuplicateWidgetNameException
import com.example.demo.domain.WidgetNotFoundException
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Errors are RFC 9457 problem documents, and each one carries the request's correlation id so a
 * user can paste the response into a ticket and the log line is one search away.
 *
 * Translating a domain failure into a status code is the web adapter's job. The domain throws
 * `WidgetNotFoundException` and has never heard of 404.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(WidgetNotFoundException::class)
    fun notFound(exception: WidgetNotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, "Widget not found", exception.message)

    @ExceptionHandler(DuplicateWidgetNameException::class)
    fun duplicate(exception: DuplicateWidgetNameException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Duplicate widget name", exception.message)

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        MDC.get(CorrelationIdFilter.MDC_KEY)?.let { problem.setProperty("correlationId", it) }
        return problem
    }
}
