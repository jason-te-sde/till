package io.till.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a name, so a log line and a caller's complaint can be joined up.
 *
 * <p>The operations guide has a symptom-to-cause table, and every row of it began with "look at the
 * logs" — which, without this, means grepping a timestamp and hoping. A caller that reports "my
 * checkout failed at 14:02" can now report an identifier instead, and it appears in the response
 * header, in the problem body of a failure, and in every log line the request produced.
 *
 * <p>An inbound {@code X-Request-Id} is honoured so a trace survives a proxy, but it is
 * <b>validated first</b>: it ends up in log lines and in a response header, and an attacker-supplied
 * newline in either is how a log is forged and a header is split. Anything that is not 8 to 64
 * characters of {@code A-Za-z0-9._-} is replaced rather than rejected, because a bad header is not a
 * reason to refuse an order.
 *
 * <p>Ordered before everything, including authentication, so that a rejected request has an
 * identifier too. "Somebody is getting 401s" is a question about requests that never reached a
 * controller.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestId extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";

    /** Where the identifier is kept for the exception handler to find. */
    static final String ATTRIBUTE = "till.requestId";

    /** The key the logging pattern reads. */
    private static final String MDC_KEY = "requestId";

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = sanitise(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Removed rather than left: the thread goes back to a pool, and the next request on it
            // would otherwise log under this one's identifier until it set its own.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * The caller's identifier if it is safe to echo, otherwise a fresh one.
     *
     * @param supplied the inbound header, or null
     * @return an identifier that is safe in a header and in a log line
     */
    static String sanitise(String supplied) {
        if (supplied == null || supplied.length() < MIN_LENGTH || supplied.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        for (int i = 0; i < supplied.length(); i++) {
            char c = supplied.charAt(i);
            boolean allowed =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '.'
                            || c == '_'
                            || c == '-';
            if (!allowed) {
                return UUID.randomUUID().toString();
            }
        }
        return supplied;
    }

    /**
     * The identifier for the request being handled.
     *
     * @param request the request
     * @return the identifier, or null if this filter did not run
     */
    static String of(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id instanceof String value ? value : null;
    }
}
