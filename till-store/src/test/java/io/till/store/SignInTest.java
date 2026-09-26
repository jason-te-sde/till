package io.till.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import io.till.store.FakeIdentityProvider.Identity;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Signing in and out the whole way round, against {@link FakeIdentityProvider}.
 *
 * <p>{@code oidcLogin()} — what the other tests use — puts a principal on the request and skips
 * everything this class is about: the redirect to the provider with PKCE and a nonce, the callback, the
 * code redeemed for tokens on the server, the ID token's signature and claims checked, the groups claim
 * turned into roles, the session created in Redis and its id rotated. Each of those is configuration
 * that could be wrong while every {@code oidcLogin()} test stayed green.
 *
 * <p>Requests here carry cookies the way a browser does — the small jar at the bottom — and nothing
 * else. CSRF is the real exchange the SPA performs, cookie to header, not the {@code csrf()} shortcut.
 */
class SignInTest extends StoreTest {

    @Autowired
    SessionRepository<?> sessions;

    @Nested
    @DisplayName("signing in")
    class SigningIn {

        @Test
        @DisplayName("sends the browser to the provider with PKCE, a nonce and a state")
        void redirect() throws Exception {
            URI authorize = new Browser().startSignIn(null);

            assertTrue(authorize.toString().startsWith(PROVIDER.authorizationUri() + "?"), authorize.toString());
            Map<String, String> query = query(authorize);
            assertEquals("S256", query.get("code_challenge_method"));
            assertNotNull(query.get("code_challenge"));
            assertNotNull(query.get("nonce"));
            assertNotNull(query.get("state"));
            assertEquals("openid profile email", query.get("scope"));
            assertEquals("http://localhost/login/oauth2/code/idp", query.get("redirect_uri"));
        }

        @Test
        @DisplayName("brings the customer back to where they started, signed in")
        void roundTrip() throws Exception {
            Browser browser = new Browser();
            URI callback = PROVIDER.approve(browser.startSignIn("/checkout"), Identity.customer("c-100"));

            MockHttpServletResponse landed = browser.get(callback);

            assertEquals(302, landed.getStatus());
            assertEquals("/checkout", landed.getRedirectedUrl());
            String me = browser.get("/api/me").getContentAsString();
            assertEquals(true, JsonPath.read(me, "$.authenticated"));
            assertEquals("Player c-100", JsonPath.read(me, "$.name"));
            assertEquals("c-100@example.test", JsonPath.read(me, "$.email"));
            assertEquals(false, JsonPath.read(me, "$.admin"));
        }

        @Test
        @DisplayName("comes back to the home page, not to another site, whatever the link asked for")
        void notAnOpenRedirect() throws Exception {
            for (String elsewhere : List.of("https://look-alike.example/login", "//look-alike.example", "/\\look-alike.example")) {
                Browser browser = new Browser();
                URI callback = PROVIDER.approve(browser.startSignIn(elsewhere), Identity.customer("c-101"));
                assertEquals("/", browser.get(callback).getRedirectedUrl(), elsewhere);
            }
        }

        @Test
        @DisplayName("makes members of the provider's admin group operators, and nobody else")
        void groupsBecomeRoles() throws Exception {
            Browser operator = signedIn(Identity.operator("o-1"));
            assertEquals(true, JsonPath.read(operator.get("/api/me").getContentAsString(), "$.admin"));
            assertEquals(200, operator.get("/api/ops/stock").getStatus());

            Browser customer = signedIn(Identity.customer("c-102"));
            assertEquals(403, customer.get("/api/ops/stock").getStatus());
        }

        @Test
        @DisplayName("never hands the browser a token — only a session cookie it cannot read")
        void noTokenReachesTheBrowser() throws Exception {
            int before = PROVIDER.issuedTokens().size();
            Browser browser = new Browser();
            URI callback = PROVIDER.approve(browser.startSignIn("/"), Identity.customer("c-103"));

            MockHttpServletResponse landed = browser.get(callback);
            MockHttpServletResponse me = browser.get("/api/me");

            List<String> tokens = PROVIDER.issuedTokens().subList(before, PROVIDER.issuedTokens().size());
            assertEquals(2, tokens.size(), "an access token and an ID token were issued, to the store");
            for (MockHttpServletResponse response : List.of(landed, me)) {
                for (String token : tokens) {
                    assertFalse(response.getContentAsString().contains(token), "a token in a response body");
                    for (String name : response.getHeaderNames()) {
                        for (String value : response.getHeaders(name)) {
                            assertFalse(value.contains(token), "a token in the " + name + " header");
                        }
                    }
                }
            }
            Cookie session = landed.getCookie("SESSION");
            assertNotNull(session);
            assertTrue(session.isHttpOnly(), "a session cookie script cannot read");
            assertEquals("Lax", session.getAttribute("SameSite"), "and that another site's form cannot send");
        }

        @Test
        @DisplayName("gives the customer a new session id, so one planted before sign-in is worthless after it")
        void sessionFixation() throws Exception {
            Browser browser = new Browser();
            URI authorize = browser.startSignIn("/");
            String planted = browser.cookie("SESSION");
            assertNotNull(planted, "starting sign-in starts a session: it holds the state and the PKCE verifier");

            browser.get(PROVIDER.approve(authorize, Identity.customer("c-104")));

            assertNotEquals(planted, browser.cookie("SESSION"));
            Browser attacker = new Browser();
            attacker.plant("SESSION", planted);
            assertEquals(false, JsonPath.read(attacker.get("/api/me").getContentAsString(), "$.authenticated"));
        }

        @Test
        @DisplayName("replaces the CSRF token at sign-in, so one captured before it is useless after")
        void csrfTokenRotates() throws Exception {
            Browser browser = new Browser();
            browser.get("/api/home");
            String before = browser.cookie("XSRF-TOKEN");
            assertNotNull(before, "a first visit is given the CSRF cookie");

            browser.get(PROVIDER.approve(browser.startSignIn("/"), Identity.customer("c-105")));
            browser.get("/api/me");

            assertNotNull(browser.cookie("XSRF-TOKEN"));
            assertNotEquals(before, browser.cookie("XSRF-TOKEN"));
            assertEquals(403, browser.post("/api/logout", before).getStatus());
        }

        @Test
        @DisplayName("refuses an ID token the provider did not sign")
        void forgedToken() throws Exception {
            Browser browser = new Browser();
            URI callback = PROVIDER.approveForged(browser.startSignIn("/checkout"), Identity.operator("mallory"));

            assertEquals("/?signin=failed", browser.get(callback).getRedirectedUrl());
            assertEquals(false, JsonPath.read(browser.get("/api/me").getContentAsString(), "$.authenticated"));
        }

        @Test
        @DisplayName("refuses a callback this browser did not start, so nobody can be signed in as somebody else")
        void callbackFromAnotherBrowser() throws Exception {
            // Login CSRF: the attacker starts a sign-in as themself, and gets the victim's browser to
            // finish it — after which the victim's purchases land in the attacker's account. The state
            // is bound to the session that started the flow, and the victim's is not that session.
            Browser attacker = new Browser();
            URI callback = PROVIDER.approve(attacker.startSignIn("/"), Identity.customer("attacker"));

            Browser victim = new Browser();
            assertEquals("/?signin=failed", victim.get(callback).getRedirectedUrl());
            assertEquals(false, JsonPath.read(victim.get("/api/me").getContentAsString(), "$.authenticated"));
        }

        @Test
        @DisplayName("lands a customer who cancels at the provider back in the store, with a flag the page explains")
        void cancelled() throws Exception {
            Browser browser = new Browser();
            URI callback = PROVIDER.deny(browser.startSignIn("/checkout"));

            assertEquals("/?signin=failed", browser.get(callback).getRedirectedUrl());
        }
    }

    @Nested
    @DisplayName("signing out")
    class SigningOut {

        @Test
        @DisplayName("deletes the session, and says where to end the provider's session too")
        void signOut() throws Exception {
            Browser browser = signedIn(Identity.customer("c-200"));
            String session = browser.cookie("SESSION");

            MockHttpServletResponse response = browser.post("/api/logout");

            assertEquals(200, response.getStatus());
            String redirect = JsonPath.read(response.getContentAsString(), "$.redirect");
            String expected = PROVIDER.logoutUri()
                    + "?client_id=till-store&post_logout_redirect_uri=http%3A%2F%2Flocalhost/&id_token_hint=";
            assertTrue(redirect.startsWith(expected), redirect);
            // The one place the ID token leaves the server, and deliberately: the logout specification
            // asks for it as a hint, and by now the session it belonged to is gone. The access token —
            // the one an API would accept — never leaves at all.
            String hint = URLDecoder.decode(redirect.substring(expected.length()), StandardCharsets.UTF_8);
            assertTrue(PROVIDER.issuedTokens().contains(hint), "the hint is this session's own ID token");

            assertNull(sessions.findById(sessionId(session)), "deleted from Redis, not merely emptied");
            assertNull(browser.cookie("SESSION"), "the cookie is cleared as well");
            assertEquals(false, JsonPath.read(browser.get("/api/me").getContentAsString(), "$.authenticated"));
        }

        @Test
        @DisplayName("needs the CSRF header, so no other site can sign a customer out")
        void signOutNeedsCsrf() throws Exception {
            Browser browser = signedIn(Identity.customer("c-201"));

            MockHttpServletResponse refused = browser.postWithoutCsrfHeader("/api/logout");

            assertEquals(403, refused.getStatus());
            assertEquals("CSRF", JsonPath.read(refused.getContentAsString(), "$.code"));
            assertEquals(true, JsonPath.read(browser.get("/api/me").getContentAsString(), "$.authenticated"));
        }
    }

    /** Signed in, and landed on the store — whose first request, like the SPA's, asks who is signed in. */
    private Browser signedIn(Identity who) throws Exception {
        Browser browser = new Browser();
        MockHttpServletResponse landed = browser.get(PROVIDER.approve(browser.startSignIn("/"), who));
        assertEquals("/", landed.getRedirectedUrl(), "signed in as " + who.subject());
        browser.get("/api/me");
        return browser;
    }

    /** Spring Session writes the id into the cookie Base64-encoded. */
    private static String sessionId(String cookie) {
        return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            values.put(
                    URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    /** Just enough of a browser: a cookie jar, and requests that carry it. */
    private final class Browser {

        private final Map<String, String> jar = new LinkedHashMap<>();

        URI startSignIn(String returnTo) throws Exception {
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get("/oauth2/authorization/idp");
            if (returnTo != null) {
                request.param("returnTo", returnTo);
            }
            MockHttpServletResponse response = send(request);
            assertEquals(302, response.getStatus());
            return URI.create(response.getRedirectedUrl());
        }

        MockHttpServletResponse get(URI uri) throws Exception {
            return send(MockMvcRequestBuilders.get(uri));
        }

        MockHttpServletResponse get(String path) throws Exception {
            return send(MockMvcRequestBuilders.get(path));
        }

        /** A write, carrying the CSRF token the way the SPA does: read from its cookie, sent as a header. */
        MockHttpServletResponse post(String path) throws Exception {
            String token = jar.get("XSRF-TOKEN");
            assertNotNull(token, "the CSRF cookie is there before the first write needs it");
            return post(path, token);
        }

        MockHttpServletResponse post(String path, String csrfToken) throws Exception {
            return send(MockMvcRequestBuilders.post(path).header("X-XSRF-TOKEN", csrfToken));
        }

        /** The request another site's page could make: the browser attaches the cookies, and that is all. */
        MockHttpServletResponse postWithoutCsrfHeader(String path) throws Exception {
            return send(MockMvcRequestBuilders.post(path));
        }

        String cookie(String name) {
            return jar.get(name);
        }

        void plant(String name, String value) {
            jar.put(name, value);
        }

        private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
            jar.forEach((name, value) -> request.cookie(new Cookie(name, value)));
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            for (Cookie cookie : response.getCookies()) {
                if (cookie.getMaxAge() == 0) {
                    jar.remove(cookie.getName());
                } else {
                    jar.put(cookie.getName(), cookie.getValue());
                }
            }
            return response;
        }
    }
}
