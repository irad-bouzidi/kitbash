package dev.kitbash.api.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One id per request, in every line the request produces (§14, §41).
 *
 * <p>§41 wants a single request traceable end to end — API, generator, job runner. The id is the
 * thread by which: it goes into the MDC, so the structured encoder puts it on every line without
 * anything having to pass it around; it comes back as a response header, so a user reporting a
 * failure can quote it; and {@code kitbash-37}'s verification runner carries it into the container,
 * because a failed user-triggered verify that cannot be traced back to its request is a failure
 * report with no beginning.
 *
 * <h2>A caller's id is accepted, within limits</h2>
 *
 * <p>Honouring an inbound {@code X-Correlation-Id} is what makes this useful behind anything else
 * that already traces — the alternative is two ids for one request and a manual join. But the value
 * reaches every log line, so it is caller-controlled input going somewhere that is grepped, shipped
 * and kept: an id with a newline in it forges log lines, and one with a megabyte in it is a
 * megabyte per line. Hence the pattern and the length, and an id that fails either is replaced
 * rather than rejected — the request is fine, its id is not.
 *
 * <p>Ordered first, so a request refused by the security filters still has one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    /** The MDC key. {@code trace.id} is what ECS calls it, which is what the encoder emits. */
    public static final String MDC_KEY = "trace.id";

    /** Long enough for a uuid or a trace id, short enough that a log line cannot be flooded. */
    private static final int LONGEST = 64;

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]+");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = acceptable(request.getHeader(HEADER))
                ? request.getHeader(HEADER)
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Removed rather than left: the thread goes back to a pool, and an id that outlives its
            // request labels somebody else's lines with it.
            MDC.remove(MDC_KEY);
        }
    }

    static boolean acceptable(String id) {
        return id != null
                && !id.isBlank()
                && id.length() <= LONGEST
                && SAFE.matcher(id).matches();
    }

    /** The id of the request being served, or null outside one. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
