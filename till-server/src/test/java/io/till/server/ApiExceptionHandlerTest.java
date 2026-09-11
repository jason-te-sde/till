package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Command;
import io.till.core.ConflictException;
import io.till.core.IdempotencyKey;
import io.till.core.IncompleteSnapshotException;
import io.till.core.Line;
import io.till.core.ReservationId;
import io.till.jdbc.LedgerException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;

/**
 * What every failure that is not an outcome looks like on the wire.
 *
 * <p>Called directly rather than through a request, because the distinction being asserted is one of
 * classification — is this the caller's fault, ours, or nobody's? — and that is decided here, in
 * eight lines per case, not by anything the servlet container does.
 *
 * <p>The case worth the most is {@link ConflictException}. It is the one failure in the system that
 * is <b>nobody's mistake</b>: the rows kept moving under a command that was fine. Answering 500 there
 * would page somebody at three in the morning about contention.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("contention is a 503 with a Retry-After, not a 500")
    void contentionIsRetryable() {
        Command command =
                new Command.Reserve(
                        IdempotencyKey.of("c1"),
                        ReservationId.of("r1"),
                        List.of(Line.of("widget", 1)),
                        Duration.ofMinutes(15));

        ResponseEntity<ProblemDetail> response = handler.onConflict(new ConflictException(command, 8));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.valueOf(response.getStatusCode().value()));
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), "come back in a second");
        ProblemDetail problem = requireBody(response);
        assertEquals("CONTENTION", problem.getProperties().get("code"));
        assertEquals(8, problem.getProperties().get("attempts"), "so an operator can see the limit being hit");
        assertTrue(problem.getDetail().contains("retried"), problem.getDetail());
    }

    @Test
    @DisplayName("a database that cannot be reached is a 503, because it is not the caller's mistake")
    void ledgerFailureIsA503() {
        ResponseEntity<ProblemDetail> response =
                handler.onLedger(
                        new LedgerException("loading a snapshot", new SQLException("connection refused")));

        assertEquals(503, response.getStatusCode().value());
        // Deliberately not the exception's message: it is an internal sentence about SQL, and the
        // caller can act on exactly one bit of it, which is "not you, try later".
        assertEquals("the ledger could not be reached", requireBody(response).getDetail());
    }

    @Test
    @DisplayName("a ledger that returns a short snapshot is a 500, because that one is our bug")
    void incompleteSnapshotIsA500() {
        ResponseEntity<ProblemDetail> response =
                handler.onIncompleteSnapshot(
                        new IncompleteSnapshotException("the ledger did not load widget"));

        // The distinction from the case above is the whole point of having two handlers: 503 says
        // "come back", 500 says "this will keep happening until somebody fixes till".
        assertEquals(500, response.getStatusCode().value());
        assertEquals("Internal error", requireBody(response).getTitle());
    }

    @Test
    @DisplayName("a bad value is a 400 that repeats the value's own complaint")
    void badArgumentIsA400() {
        ResponseEntity<ProblemDetail> response =
                handler.onBadRequest(new IllegalArgumentException("a quantity must be positive, got 0"));

        assertEquals(400, response.getStatusCode().value());
        // Passed through, unlike the ledger's message: these come from value types rejecting input
        // the caller sent, so the sentence is about the caller's request and useful to them.
        assertEquals("a quantity must be positive, got 0", requireBody(response).getDetail());
    }

    @Test
    @DisplayName("a missing Idempotency-Key says which header and why it is not optional")
    void missingHeaderExplainsItself() throws NoSuchMethodException {
        MissingRequestHeaderException missing =
                new MissingRequestHeaderException(
                        "Idempotency-Key",
                        new MethodParameter(
                                ApiExceptionHandlerTest.class.getDeclaredMethod("aMethodWithAParameter", String.class),
                                0));

        ResponseEntity<ProblemDetail> response = handler.onMissingHeader(missing);

        assertEquals(400, response.getStatusCode().value());
        String detail = requireBody(response).getDetail();
        assertTrue(detail.startsWith("Idempotency-Key"), detail);
        // The "why" matters more than the "what". Somebody hitting this is about to add the header
        // as a random UUID per attempt, which defeats the point of requiring it.
        assertTrue(detail.contains("retrying one is safe"), detail);
    }

    @SuppressWarnings("unused")
    private void aMethodWithAParameter(String header) {}

    private static ProblemDetail requireBody(ResponseEntity<ProblemDetail> response) {
        ProblemDetail problem = response.getBody();
        assertNotNull(problem, "every failure has a body; a bare status tells a caller nothing");
        return problem;
    }
}
