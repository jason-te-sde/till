package io.till.store;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Who may do what, asserted from the outside.
 *
 * <p>Every rule in the security configuration has a test here that fails if the rule is deleted —
 * which is the point of writing them as requests rather than reading the configuration and agreeing
 * with it. Signing in and out, which needs a provider, is {@link SignInTest}'s.
 */
class SecurityTest extends StoreTest {

    @Nested
    @DisplayName("a visitor")
    class Visitor {

        @Test
        @DisplayName("can browse the whole catalogue")
        void browses() throws Exception {
            mvc.perform(get("/api/home")).andExpect(status().isOk());
            mvc.perform(get("/api/games")).andExpect(status().isOk());
            mvc.perform(get("/api/games/tessera")).andExpect(status().isOk());
            mvc.perform(get("/api/genres")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("is told they are signed out — as an answer, not an error")
        void me() throws Exception {
            mvc.perform(get("/api/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(false))
                    .andExpect(jsonPath("$.admin").value(false))
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
        }

        @Test
        @DisplayName("asking for orders gets a 401 problem, not a redirect to a login page")
        void apiIsNotRedirected() throws Exception {
            // A fetch would follow a redirect and hand the SPA an HTML login form where it expected JSON.
            mvc.perform(get("/api/orders"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("cannot reach the operator console")
        void noOps() throws Exception {
            mvc.perform(get("/api/ops/stock")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("can read the API's description")
        void apiDocs() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("a customer")
    class Customer {

        @Test
        @DisplayName("is told who they are")
        void me() throws Exception {
            mvc.perform(get("/api/me").with(customer("alice")))
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andExpect(jsonPath("$.name").value("Player alice"))
                    .andExpect(jsonPath("$.email").value("alice@example.test"))
                    .andExpect(jsonPath("$.admin").value(false));
        }

        @Test
        @DisplayName("cannot reach the operator console, and is told so in the same shape as every error")
        void noOps() throws Exception {
            mvc.perform(get("/api/ops/stock").with(customer("alice")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("cannot change anything without the CSRF token — the session cookie alone is not enough")
        void csrfIsRequired() throws Exception {
            // A cookie the browser attaches automatically is exactly what a cross-site request would
            // carry too. The header is what another site cannot produce.
            mvc.perform(post("/api/orders")
                            .with(customer("alice"))
                            .header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF"));
        }

        @Test
        @DisplayName("is given the CSRF cookie on the first request, before any write needs it")
        void csrfCookieIsIssued() throws Exception {
            // Readable from script on purpose: the SPA copies it into a header, which a page on another
            // origin cannot do. Issued eagerly, because a deferred token is never written until
            // something reads it — and the SPA's first write would fail for want of a cookie.
            mvc.perform(get("/api/home"))
                    .andExpect(cookie().exists("XSRF-TOKEN"))
                    .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
        }
    }

    @Nested
    @DisplayName("an operator")
    class Operator {

        @Test
        @DisplayName("is told they are one")
        void me() throws Exception {
            mvc.perform(get("/api/me").with(operator("olivia"))).andExpect(jsonPath("$.admin").value(true));
        }

        @Test
        @DisplayName("can reach the operator console")
        void ops() throws Exception {
            mvc.perform(get("/api/ops/stock").with(operator("olivia"))).andExpect(status().isOk());
        }
    }
}
