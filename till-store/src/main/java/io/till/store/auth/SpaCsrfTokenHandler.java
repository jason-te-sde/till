package io.till.store.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * How a CSRF token is read from a request, for a single-page application — with the token deferred.
 *
 * <p>The SPA sends the token back in the {@code X-XSRF-TOKEN} header exactly as it found it in the
 * cookie, so a header is compared as-is. Anything else — a form parameter — is read as the masked,
 * BREACH-resistant form Spring Security renders into pages.
 *
 * <p>Spring Security's {@code csrf.spa()} does the same, and also loads the token on every request:
 * its handler asks the deferred token for its parameter name, which generates it, which sets the
 * cookie. That includes the catalogue's responses, which are marked for any cache to share — and a
 * shared response must never carry a {@code Set-Cookie}. So this handler leaves the token deferred,
 * and {@link CsrfCookieFilter} loads it everywhere a response is private.
 */
final class SpaCsrfTokenHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler masked = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
        masked.handle(request, response, token);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        return StringUtils.hasText(request.getHeader(token.getHeaderName()))
                ? plain.resolveCsrfTokenValue(request, token)
                : masked.resolveCsrfTokenValue(request, token);
    }
}
