package io.till.store.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.store.ledger.LedgerUnavailableException;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * What the store says when the ledger cannot be asked.
 *
 * <p>{@code @Isolated} for the reason {@code ApiExceptionHandlerTest} gives: {@link CapturedOutput}
 * replaces {@code System.out} for the span of a test, and other classes' logging would race on it.
 */
@ExtendWith(OutputCaptureExtension.class)
@Isolated
class ProblemsTest {

    private final Problems problems = new Problems();

    @Test
    @DisplayName("an unreachable ledger is logged with what failed underneath: a timeout is not a refused connection")
    void anUnreachableLedgerIsLoggedWithItsCause(CapturedOutput output) {
        var e = new LedgerUnavailableException("reserving 1 lines: the ledger could not be reached",
                new UncheckedIOException("gave up calling http://ledger:8080/v1/reservations after 2 attempts in 5003 ms",
                        new HttpTimeoutException("request timed out")));

        var response = problems.unavailable(e);

        assertEquals(503, response.getStatusCode().value());
        assertTrue(output.getOut().contains("after 2 attempts in 5003 ms"), output.getOut());
        assertTrue(output.getOut().contains("HttpTimeoutException: request timed out"), output.getOut());
    }
}
