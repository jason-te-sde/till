package io.till.store.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes sure the browser has the CSRF cookie before it needs it.
 *
 * <p>Spring Security defers the CSRF token: the {@code XSRF-TOKEN} cookie is written only when
 * something asks for the token's value. A server-rendered form asks while rendering; an SPA never does
 * — it reads the cookie and copies it into a header. So without this, the cookie first appears in the
 * response to the SPA's first write, which has just been refused for lacking it, and the customer's
 * first "Add to order" fails.
 *
 * <p>Reading the token here, on every request, writes the cookie whenever the browser does not have
 * one — on the first page load, and again on the first request after signing in, when Spring Security
 * replaces the token so that one captured before sign-in is useless after it.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

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
