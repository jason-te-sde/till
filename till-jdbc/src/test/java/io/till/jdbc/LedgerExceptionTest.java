package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing callers need to tell apart within {@link LedgerException}: whether it is Hikari's
 * pool refusing to grow under load — overload, not a fault — or anything else the database could
 * throw.
 */
class LedgerExceptionTest {

    @Test
    @DisplayName("the pool's own exception is found when it is the direct cause")
    void poolExhaustionIsTheDirectCause() {
        SQLTransientConnectionException exhausted =
                new SQLTransientConnectionException(
                        "till - Connection is not available, request timed out after 2000ms "
                                + "(total=16, active=16, idle=0, waiting=184)");
        LedgerException e = new LedgerException("applying reserved", exhausted);

        assertTrue(e.poolExhaustion().isPresent(), e.poolExhaustion().toString());
        assertEquals(exhausted, e.poolExhaustion().get());
    }

    @Test
    @DisplayName("the pool's own exception is found further up the cause chain, not only as the direct cause")
    void poolExhaustionIsFoundThroughWrapping() {
        SQLTransientConnectionException exhausted = new SQLTransientConnectionException("timed out");
        SQLException wrapper = new SQLException("batch failed", exhausted);
        LedgerException e = new LedgerException("applying reserved", wrapper);

        assertTrue(e.poolExhaustion().isPresent());
        assertEquals(exhausted, e.poolExhaustion().get());
    }

    @Test
    @DisplayName("an ordinary database failure is not pool exhaustion")
    void anOrdinaryFailureIsNotPoolExhaustion() {
        LedgerException e = new LedgerException("loading a snapshot", new SQLException("connection refused"));

        assertFalse(e.poolExhaustion().isPresent());
    }
}
