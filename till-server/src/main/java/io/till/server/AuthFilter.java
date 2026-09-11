package io.till.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer tokens, at two levels.
 *
 * <p>Every {@code /v1} endpoint needs the client token. The endpoints that change stock levels
 * directly need the admin token as well, because a checkout service that can write off inventory is
 * a checkout service whose compromise costs more than a day of orders.
 *
 * <p>Tokens are compared in constant time. The comparison is not the interesting attack surface here
 * — an attacker who can measure it can also just make requests — but a variable-time compare on a
 * secret is the kind of thing that gets copied into somewhere it does matter.
 *
 * <p>Deliberately not Spring Security. Two static tokens do not need a filter chain, an
 * authentication manager and a servlet integration, and a dependency whose surface is that large
 * needs a reason better than "it is what people use".
 */
@Component
class AuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final TillProperties properties;

    AuthFilter(TillProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TillProperties.Auth auth = properties.auth();
        if (!auth.isConfigured()) {
            // No token configured at all. ExposureCheck has already refused to start in the cases
            // where that is dangerous, so this is a laptop and everything is allowed.
            chain.doFilter(request, response);
            return;
        }

        String presented = bearer(request);
        if (presented == null) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "a bearer token is required");
            return;
        }
        boolean isAdmin = auth.isAdminConfigured() && matches(presented, auth.adminToken());
        boolean isClient = matches(presented, auth.clientToken());
        if (!isAdmin && !isClient) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "that token is not recognised");
            return;
        }
        if (needsAdmin(request) && auth.isAdminConfigured() && !isAdmin) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "changing stock levels needs the admin token");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The endpoints the client token is not enough for.
     *
     * <p>Two of them: moving stock without a reservation behind it, and reading the outbox. The
     * backlog is not a secret, but the payloads are the full history of what moved and when, which
     * is more than a checkout service has any reason to read.
     */
    private static boolean needsAdmin(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/v1/outbox")) {
            return true;
        }
        // Only POST: listing and reading levels is something the shop front does on every page.
        return path.startsWith("/v1/stock/") && "POST".equals(request.getMethod());
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String value = header.substring(BEARER.length()).trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean matches(String presented, String configured) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), configured.getBytes(StandardCharsets.UTF_8));
    }

    private static void deny(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write(
                        "{\"type\":\"about:blank\",\"title\":\"" + (status == 401 ? "Unauthorized" : "Forbidden")
                                + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
