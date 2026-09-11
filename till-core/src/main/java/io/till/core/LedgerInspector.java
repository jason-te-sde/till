package io.till.core;

import java.util.List;
import java.util.Optional;

/**
 * A read-only view of everything a ledger holds.
 *
 * <p>Separate from {@link Ledger} because nothing on the command path needs it: deciding a command
 * reads a {@link Snapshot} scoped to that command, and a full view would be the wrong shape and the
 * wrong cost. This exists for the two callers that genuinely want everything — the invariant checker,
 * which has to see the whole ledger to know whether it adds up, and an administrative listing.
 *
 * <p>Two kinds of method live here and the difference matters. {@code stock} and {@code reservation}
 * are point lookups on a primary key and are cheap enough for a request path. {@code listStock} and
 * {@code listReservations} are bounded listings, backed by an index and capped by {@link #MAX_PAGE};
 * they are meant for an operator's screen. {@code allStock}, {@code allReservations} and
 * {@code allEvents} scan everything and have no bound at all — they exist for the invariant checker,
 * which has to see the whole ledger to know whether it adds up, and they have no business on a
 * request path.
 */
public interface LedgerInspector {

    /**
     * Most rows a single listing may return.
     *
     * <p>A listing that can return a million rows is a listing that will one day be asked to, by
     * something that then holds all of them in memory on both sides.
     */
    int MAX_PAGE = 500;

    /**
     * One SKU's level.
     *
     * @param sku which SKU
     * @return the level, or empty if the SKU has no row
     */
    Optional<StockItem> stock(Sku sku);

    /**
     * One reservation.
     *
     * @param id which reservation
     * @return the reservation, or empty if there is none
     */
    Optional<Reservation> reservation(ReservationId id);

    /**
     * A page of stock levels, in SKU order.
     *
     * <p>Keyset pagination rather than an offset: an offset re-reads and discards everything before
     * it, so paging through a large table gets slower as it goes, and a row inserted during the walk
     * shifts every later page by one.
     *
     * @param after the last SKU of the previous page, or empty to start
     * @param limit how many at most, clamped to {@link #MAX_PAGE}
     * @return the page, in SKU order
     */
    List<StockItem> listStock(Optional<Sku> after, int limit);

    /**
     * A page of the most recently created reservations, newest first.
     *
     * <p>Newest first because that is what an operator is looking for, and because the alternative —
     * oldest first — puts the rows nobody cares about on the first page forever.
     *
     * @param state only reservations in this state, or empty for all
     * @param limit how many at most, clamped to {@link #MAX_PAGE}
     * @return the page, newest first
     */
    List<Reservation> listReservations(Optional<ReservationState> state, int limit);

    /**
     * Every stock level, in SKU order.
     *
     * @return a consistent copy
     */
    List<StockItem> allStock();

    /**
     * Every reservation, in id order.
     *
     * @return a consistent copy
     */
    List<Reservation> allReservations();

    /**
     * Every outbox entry ever written, in sequence order.
     *
     * @return a consistent copy
     */
    List<OutboxEntry> allEvents();
}
