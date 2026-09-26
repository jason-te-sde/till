package io.till.store.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The open-redirect checks, one spelling at a time.
 *
 * <p>Each refused value below is a way a string that starts out looking like a path on this site ends
 * up, after some browser or proxy has normalised it, naming another host.
 */
class ReturnToTest {

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {"/", "/checkout", "/games/sunless-orbit", "/browse?genre=Puzzle&sort=price-asc",
            "/browse?q=rust%20and%20rain", "/orders#latest"})
    @DisplayName("accepts a path on this site")
    void accepts(String path) {
        assertTrue(ReturnTo.isSafe(path));
    }

    @ParameterizedTest(name = "refuses {0}")
    @NullAndEmptySource
    @ValueSource(strings = {
        "checkout",                            // not from the root
        "https://look-alike.example/login",    // another site, plainly
        "//look-alike.example",                // protocol-relative: another site
        "/\\look-alike.example",               // read as "//" by browsers that normalise backslashes
        "/\t/look-alike.example",              // a tab some parsers strip, leaving "//"
        "/\n/look-alike.example",              // likewise a newline
        "/%2F/look-alike.example",             // an encoded slash, decoded later into "//"
        "/%2f/look-alike.example",             // in either case
        "/%5Clook-alike.example",              // an encoded backslash
        "/%09/look-alike.example",             // an encoded tab
        "/%0a/look-alike.example",             // an encoded newline
        "/path\\with\\backslashes",            // no backslash anywhere
        "javascript:alert(document.cookie)",   // a scheme, and not a path
        "/ has a space"                        // not a URI a browser would send
    })
    @DisplayName("refuses anything that is, or could become, somewhere else")
    void refuses(String value) {
        assertFalse(ReturnTo.isSafe(value));
    }

    @Test
    @DisplayName("refuses an absurdly long path rather than carry it through a session")
    void refusesLongPaths() {
        assertTrue(ReturnTo.isSafe("/" + "a".repeat(511)));
        assertFalse(ReturnTo.isSafe("/" + "a".repeat(512)));
    }

    @Test
    @DisplayName("is used once: the second sign-in in a session does not inherit the first one's destination")
    void consumedOnce() {
        MockHttpServletRequest start = new MockHttpServletRequest();
        start.setParameter("returnTo", "/checkout");
        ReturnTo.remember(start);

        MockHttpServletRequest callback = new MockHttpServletRequest();
        callback.setSession(start.getSession());
        assertEquals("/checkout", ReturnTo.consume(callback));
        assertEquals("/", ReturnTo.consume(callback));
    }

    @Test
    @DisplayName("an unsafe value replaces a safe one remembered earlier, rather than leaving it in place")
    void unsafeForgetsEarlier() {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setParameter("returnTo", "/checkout");
        ReturnTo.remember(first);

        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setSession(first.getSession());
        second.setParameter("returnTo", "https://look-alike.example");
        ReturnTo.remember(second);

        MockHttpServletRequest callback = new MockHttpServletRequest();
        callback.setSession(first.getSession());
        assertEquals("/", ReturnTo.consume(callback));
    }

    @Test
    @DisplayName("without a session, goes home")
    void noSession() {
        assertEquals("/", ReturnTo.consume(new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("checks again on the way out, whatever put the value in the session")
    void checkedOnTheWayOut() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ReturnTo.ATTRIBUTE, "//look-alike.example");
        assertEquals("/", ReturnTo.consume(request));
    }
}
