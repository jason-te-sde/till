package io.till.store.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 401 and 403 an API client gets, in the same RFC 9457 shape as every other error here.
 *
 * <p>Spring Security's defaults answer an unauthenticated API call with a redirect to the sign-in
 * page — right for a browser navigating to a page, and wrong for {@code fetch}, which follows the
 * redirect and hands the SPA an HTML login form where it expected JSON. Under {@code /api} the answer
 * is a status and a body, and the SPA decides what to do with it.
 */
@Component
class ProblemResponses {

    private final JsonMapper json;

    ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    void unauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, 401, "Unauthorized", "sign in to continue", "UNAUTHORIZED");
    }

    void forbidden(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        if (e instanceof CsrfException) {
            // Its own code, because the fix is different: not "you may not", but "reload the page" —
            // almost always a tab left open across a session that has since ended.
            write(response, 403, "Forbidden", "the request did not carry a valid CSRF token; reload the page and try again", "CSRF");
            return;
        }
        write(response, 403, "Forbidden", "your account cannot do this", "FORBIDDEN");
    }

    private void write(HttpServletResponse response, int status, String title, String detail, String code)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("code", code);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
