package com.example.demo.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Puts a correlation id on every request and every log line written while handling it. An id
 * supplied by the caller is honoured, so a trace survives a hop between services; otherwise one is
 * generated. The id is echoed back so the caller can quote it in a bug report.
 */
@Component
@Order(1)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val correlationId = sanitize(request.getHeader(HEADER))
        MDC.put(MDC_KEY, correlationId)
        response.setHeader(HEADER, correlationId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    /** A caller-supplied id reaches the log file, so it is length-capped and stripped of noise. */
    private fun sanitize(supplied: String?): String {
        if (supplied.isNullOrBlank()) {
            return UUID.randomUUID().toString()
        }
        val cleaned = supplied.replace(NOISE, "")
        if (cleaned.isEmpty()) {
            return UUID.randomUUID().toString()
        }
        return cleaned.take(MAX_LENGTH)
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val MDC_KEY = "correlationId"

        private const val MAX_LENGTH = 64
        private val NOISE = Regex("[^A-Za-z0-9_.-]")
    }
}
