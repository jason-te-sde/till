package io.till.store.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives the browser the CSRF cookie on every private response, and on no public one.
 *
 * <p>The token is deferred (see {@link SpaCsrfTokenHandler}): the {@code XSRF-TOKEN} cookie is written
 * only when something asks for the token's value. A server-rendered form asks while rendering; an SPA
 * never does — it reads the cookie and copies it into a header. So this asks, on every request whose
 * response is private, and the cookie is there before the SPA's first write needs it — including the
 * first request after signing in, when Spring Security replaces the token so that one captured before
 * sign-in is useless after it.
 *
 * <p>Not on the catalogue: those responses are marked for any cache to share, and a shared response
 * that sets a cookie is either refused by the cache or, worse, stored and handed to the next visitor.
 * The SPA's first request is always the session, which is private, so it still gets its cookie first.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    private final RequestMatcher publicResponses;

    /**
     * @param publicResponses the requests answered with a response any cache may share
     */
    CsrfCookieFilter(RequestMatcher publicResponses) {
        this.publicResponses = publicResponses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return publicResponses.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            // The value itself is not needed here; asking for it is what renders the cookie.
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
