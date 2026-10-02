package io.till.jdbc;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;

/**
 * The database could not be reached, or refused something that should not have been refusable.
 *
 * <p>Deliberately not thrown for the ordinary refusals. A version that has moved, an idempotency key
 * that was claimed first, a reservation id already taken — those are what {@code apply} returning
 * {@code false} means, and the caller answers them by deciding again. This is for everything else: a
 * connection that dropped, a table that is not there, a check constraint the application should never
 * have been able to violate — and the one case with its own name because it is not a fault either,
 * {@link #poolExhaustion()}: the pool itself refusing to grow under load.
 *
 * <p>The check-violation case is the reason a check violation is not treated as a conflict. Retrying
 * it would hide the bug that produced it behind a loop that never terminates.
 */
public class LedgerException extends RuntimeException {

    /**
     * @param message what was being attempted
     * @param cause what the driver said
     */
    public LedgerException(String message, SQLException cause) {
        super(message + ": " + cause.getMessage() + " [SQLState " + cause.getSQLState() + "]", cause);
    }

    /**
     * Whether this failure is the connection pool refusing to grow under load — too many commands
     * waiting for too few pooled connections — rather than a fault in the database or the driver.
     *
     * <p>Walks the whole cause chain rather than checking only the direct cause, because a batch or
     * transaction failure can wrap the pool's own exception inside another {@link SQLException}.
     *
     * @return Hikari's own {@link SQLTransientConnectionException}, a standard JDBC type and not a
     *     Hikari-specific one, if it is this exception's cause or anywhere in that cause's own
     *     chain; empty if this is some other failure
     */
    public Optional<SQLTransientConnectionException> poolExhaustion() {
        for (Throwable cause = getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException exhausted) {
                return Optional.of(exhausted);
            }
        }
        return Optional.empty();
    }
}
