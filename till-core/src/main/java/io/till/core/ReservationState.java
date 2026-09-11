package io.till.core;

/**
 * Where a reservation is in its life.
 *
 * <p>{@link #HELD} is the only state that can change, and it can go to any of the other three. The
 * other three are terminal: stock that has left the building cannot be un-committed, and a hold that
 * was dropped is not re-taken by asking again.
 *
 * <p>A stored state of {@code HELD} is not the last word. A hold whose deadline has passed is
 * expired whether or not anything has written that down yet — see
 * {@link Reservation#effectiveState(java.time.Instant)}.
 */
public enum ReservationState {

    /** Stock is set aside for this reservation and counted in {@link StockItem#reserved()}. */
    HELD,

    /** The goods left: both on-hand and reserved went down by the reserved quantity. */
    COMMITTED,

    /** The hold was given up on purpose. Reserved went down; on-hand did not. */
    RELEASED,

    /** The hold ran out of time. Identical to {@link #RELEASED} in its effect on stock. */
    EXPIRED;

    /**
     * Whether no further command can change this state.
     *
     * @return true for everything except {@link #HELD}
     */
    public boolean isTerminal() {
        return this != HELD;
    }
}
