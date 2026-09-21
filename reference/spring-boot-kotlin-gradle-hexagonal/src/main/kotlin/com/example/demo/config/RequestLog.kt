package com.example.demo.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * One structured line per request: method, path, status, duration.
 *
 * Without this the correlation id is a field on log lines nobody writes. A successful request
 * through a well-behaved service logs nothing at all, so the id that was so carefully put in the
 * MDC only ever appears when something has already gone wrong — which is the half of the incident
 * where it is least useful. An access line means "what happened to request X" is answerable.
 *
 * The id is not passed as an argument: it is in the MDC, and the ECS encoder puts every MDC entry
 * on the line as a field. That is what makes it searchable rather than a string somebody has to
 * grep out of a message.
 *
 * Ordered after [CorrelationIdFilter] so the id is already there, and it times the whole chain
 * below it rather than the handler alone — which is the number a caller experiences.
 */
@Component
@Order(3)
class RequestLog : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val started = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            log
                .atInfo()
                .addKeyValue("http.request.method", request.method)
                .addKeyValue("url.path", request.requestURI)
                .addKeyValue("http.response.status_code", response.status)
                .addKeyValue("event.duration_ms", (System.nanoTime() - started) / 1_000_000)
                .log("handled request")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RequestLog::class.java)
    }
}
