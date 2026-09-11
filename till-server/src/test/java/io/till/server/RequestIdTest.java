package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The identifier filter, which is the one piece of this service that takes a value from an untrusted
 * caller and puts it into a log line and a response header.
 *
 * <p>No Spring context: it is a filter and a static function, and starting the whole application to
 * ask what it does with a newline would be slower and prove less.
 */
class RequestIdTest {

    @Test
    @DisplayName("a well-formed identifier survives, so a trace can cross a proxy")
    void honoursAGoodOne() {
        assertEquals("abc-123_456.789", RequestId.sanitise("abc-123_456.789"));
    }

    @ParameterizedTest
    @DisplayName("anything that could forge a log line or split a header is replaced")
    @ValueSource(
            strings = {
                // The two that matter. A newline in a log line invents a second log line; a carriage
                // return in a header value ends the header and starts whatever follows it.
                "trace\nINFO fake log line",
                "trace\r\nSet-Cookie: admin=1",
                // And the ordinary ways a value gets in that nobody meant to allow.
                "trace id with spaces",
                "<script>alert(1)</script>",
                "../../etc/passwd",
                "semi;colon;separated"
            })
    void replacesAnythingDangerous(String supplied) {
        String id = RequestId.sanitise(supplied);

        assertNotEquals(supplied, id);
        assertEquals(id, UUID.fromString(id).toString(), "the replacement is a fresh UUID");
    }

    @Test
    @DisplayName("too short and too long are both replaced, at the exact boundaries")
    void boundsAreEnforced() {
        // Short ones because a one-character identifier does not identify anything; long ones because
        // a caller should not be able to put sixty kilobytes into every log line a request writes.
        assertNotEquals("abc", RequestId.sanitise("abc"));
        assertEquals("12345678", RequestId.sanitise("12345678"), "eight is the minimum, inclusive");
        String sixtyFour = "a".repeat(64);
        assertEquals(sixtyFour, RequestId.sanitise(sixtyFour), "sixty-four is the maximum, inclusive");
        assertNotEquals("a".repeat(65), RequestId.sanitise("a".repeat(65)));
    }

    @Test
    @DisplayName("no header at all gets a fresh identifier rather than nothing")
    void mintsOneWhenAbsent() {
        String id = RequestId.sanitise(null);

        assertEquals(id, UUID.fromString(id).toString());
    }

    @Test
    @DisplayName("two requests with no header get different identifiers")
    void identifiersAreDistinct() {
        assertNotEquals(RequestId.sanitise(null), RequestId.sanitise(null));
    }

    @Test
    @DisplayName("the identifier goes on the response, on the request, and in the logging context")
    void publishesItEverywhere() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestId.HEADER, "known-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenByTheChain = new String[1];
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(ServletRequest req, ServletResponse res) {
                        seenByTheChain[0] = MDC.get("requestId");
                    }
                };

        new RequestId().doFilter(request, response, chain);

        assertEquals("known-trace-id", response.getHeader(RequestId.HEADER), "so a caller can quote it");
        assertEquals("known-trace-id", RequestId.of(request), "so the exception handler can put it in the body");
        assertEquals("known-trace-id", seenByTheChain[0], "so every log line the request writes carries it");
    }

    @Test
    @DisplayName("the logging context is cleared afterwards, even when the request throws")
    void clearsTheContext() {
        MockFilterChain explodes =
                new MockFilterChain() {
                    @Override
                    public void doFilter(ServletRequest req, ServletResponse res) {
                        throw new IllegalStateException("the controller threw");
                    }
                };

        assertTrue(
                runAndReportThrew(explodes),
                "the exception is not swallowed; the point is only what the finally block does");

        // The thread goes back to a pool. Left set, the next request on it would log under this
        // one's identifier until it set its own — wrong rather than missing, which is worse.
        assertNull(MDC.get("requestId"));
    }

    @Test
    @DisplayName("a request the filter never saw reports no identifier rather than inventing one")
    void noAttributeMeansNull() {
        assertNull(RequestId.of(new MockHttpServletRequest()));

        MockHttpServletRequest wrongType = new MockHttpServletRequest();
        wrongType.setAttribute(RequestId.ATTRIBUTE, 42);
        assertNull(RequestId.of(wrongType), "something else under that key is not an identifier");
    }

    private boolean runAndReportThrew(MockFilterChain chain) {
        try {
            new RequestId().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
            return false;
        } catch (RuntimeException | ServletException | IOException expected) {
            return true;
        }
    }
}
