package io.till.jdbc;

import java.sql.SQLException;

/**
 * The database could not be reached, or refused something that should not have been refusable.
 *
 * <p>Deliberately not thrown for the ordinary refusals. A version that has moved, an idempotency key
 * that was claimed first, a reservation id already taken — those are what {@code apply} returning
 * {@code false} means, and the caller answers them by deciding again. This is for everything else: a
 * connection that dropped, a table that is not there, a check constraint the application should never
 * have been able to violate.
 *
 * <p>That last one is the reason a check violation is not treated as a conflict. Retrying it would
 * hide the bug that produced it behind a loop that never terminates.
 */
public class LedgerException extends RuntimeException {

    /**
     * @param message what was being attempted
     * @param cause what the driver said
     */
    public LedgerException(String message, SQLException cause) {
        super(message + ": " + cause.getMessage() + " [SQLState " + cause.getSQLState() + "]", cause);
    }
}
