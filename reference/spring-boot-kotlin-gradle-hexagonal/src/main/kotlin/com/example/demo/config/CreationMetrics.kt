package com.example.demo.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * One metric that is about this application rather than about the JVM it runs in.
 *
 * `jvm_memory_used_bytes` tells you the process is alive. `widgets_created_total` tells you whether
 * anybody is using it, which is the number somebody actually alerts on — and a generated project
 * that ships only the first kind has not really been instrumented.
 *
 * Counted at the edge, from the response, rather than inside the service. That is not laziness: the
 * service lives in a different package in each of the three architectures, so a counter there would
 * be three files instead of one, and "a widget was created" is exactly what a 201 on this path
 * means. If you later need to count something the response cannot see, that is the moment to move
 * the counter inwards and accept the per-architecture cost.
 */
@Component
@Order(2)
class CreationMetrics(
    meters: MeterRegistry,
) : OncePerRequestFilter() {
    private val created: Counter =
        Counter
            .builder("widgets.created")
            .description("Widgets created through the API")
            .baseUnit("widgets")
            .register(meters)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        chain.doFilter(request, response)
        if (request.method == "POST" &&
            request.requestURI == "/api/widgets" &&
            response.status == HttpStatus.CREATED.value()
        ) {
            created.increment()
        }
    }
}
