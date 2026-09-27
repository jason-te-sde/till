package io.till.store.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where to send a customer after they sign in — without becoming an open redirect.
 *
 * <p>Sign-in starts with {@code /oauth2/authorization/idp?returnTo=/checkout}, so a customer who was
 * sent to sign in from the checkout comes back to the checkout instead of the home page. The value is
 * remembered in the session across the round trip to the identity provider and used once.
 *
 * <p>The danger is obvious once said: an unchecked {@code returnTo} turns this site's own sign-in link
 * into a way to send its customers anywhere — {@code ?returnTo=https://look-alike.example} — straight
 * after they have typed their password and trust whatever loads next. So only a path on this site is
 * accepted, and every way a path can quietly become a different host is refused.
 */
public final class ReturnTo {

    static final String ATTRIBUTE = ReturnTo.class.getName();
    static final String PARAMETER = "returnTo";
    private static final int MAX_LENGTH = 512;

    private ReturnTo() {}

    /**
     * @param value what the request asked for
     * @return whether it is a path on this site and nothing else
     */
    public static boolean isSafe(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        // A path, from the root.
        if (value.charAt(0) != '/') {
            return false;
        }
        // "//host" is a protocol-relative URL: another site. "/\host" is read the same way by more
        // than one browser, because they normalise the backslash before resolving.
        if (value.length() > 1 && (value.charAt(1) == '/' || value.charAt(1) == '\\')) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Control characters, including tab and newline, which some parsers strip — turning
            // "/\t/host" into "//host" after it has been checked.
            if (Character.isISOControl(c) || c == '\\') {
                return false;
            }
        }
        // Percent-encoded slashes and backslashes, for the same reason: decoded later, they become
        // the separators the checks above refused.
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%09") || lower.contains("%0a")
                || lower.contains("%0d")) {
            return false;
        }
        try {
            URI uri = new URI(value);
            // Belt and braces: however it was spelled, it must parse as a path with no host.
            return uri.getScheme() == null && uri.getAuthority() == null && uri.getHost() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Remembers where to return, if it is safe; forgets any earlier value if not.
     *
     * @param request the request that started sign-in
     */
    static void remember(HttpServletRequest request) {
        String value = request.getParameter(PARAMETER);
        HttpSession session = request.getSession(true);
        if (isSafe(value)) {
            session.setAttribute(ATTRIBUTE, value);
        } else {
            session.removeAttribute(ATTRIBUTE);
        }
    }

    /**
     * Where to go now, used once.
     *
     * @param request the request that completed sign-in
     * @return the remembered path, or the home page
     */
    static String consume(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return "/";
        }
        Object value = session.getAttribute(ATTRIBUTE);
        session.removeAttribute(ATTRIBUTE);
        // Checked again on the way out: the session is ours, but checking twice costs nothing and
        // means this method is safe whatever put the value there.
        return value instanceof String path && isSafe(path) ? path : "/";
    }
}
