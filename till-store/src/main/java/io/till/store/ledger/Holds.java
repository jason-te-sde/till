package io.till.store.ledger;

import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.ReservationId;
import java.time.Duration;
import java.util.List;

/**
 * The three things a store asks the ledger to do.
 *
 * <p>A port rather than the HTTP client directly, and not for the usual reason of swapping databases.
 * It is so the order logic can be tested against the <b>real reservation kernel</b> running in memory —
 * the same {@code Till} and the same rules the service runs, with no HTTP between them — instead of
 * against a stub that behaves the way whoever wrote it expected the ledger to behave.
 *
 * <p>Refusals are values, not exceptions: an out-of-stock basket is an answer, and a caller should
 * have to look at it. Only "the ledger could not be reached" is thrown, as
 * {@link LedgerUnavailableException}, because there is nothing to look at.
 */
public interface Holds {

    /**
     * Takes a hold.
     *
     * @param key namespaced to the customer; see {@link LedgerKeys}
     * @param lines what to hold, at most one line per game
     * @param holdFor how long the hold lasts
     * @return {@link Outcome.Reserved} or {@link Outcome.Rejected}
     */
    Outcome reserve(IdempotencyKey key, List<Line> lines, Duration holdFor);

    /**
     * Turns a hold into a sale.
     *
     * @param key namespaced to the customer
     * @param id the hold
     * @return {@link Outcome.Committed} or {@link Outcome.Rejected}
     */
    Outcome commit(IdempotencyKey key, ReservationId id);

    /**
     * Gives a hold back.
     *
     * @param key namespaced to the customer
     * @param id the hold
     * @return {@link Outcome.Released} or {@link Outcome.Rejected}
     */
    Outcome release(IdempotencyKey key, ReservationId id);
}
