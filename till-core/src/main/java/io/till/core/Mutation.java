package io.till.core;

/**
 * One row change a decision needs written.
 *
 * <p>Mutations are what makes the kernel's output executable without the kernel touching a database.
 * A {@link Ledger} applies the whole list of them, the events beside them, and the idempotency record
 * beside those, in <b>one transaction</b>, and refuses the lot if any expected version has moved.
 * Applying them in pieces is the mistake the type exists to make visible: stock lowered without the
 * reservation moving to {@code COMMITTED} is stock that no longer exists and is still promised.
 *
 * <p>Every mutation carries the version it expects, taken from the snapshot the decision was made
 * against. That is the whole concurrency control: two callers that load the same row and decide
 * against it produce the same expected version, one of them applies first, and the other is told to
 * start again with what is now there.
 */
public sealed interface Mutation {

    /**
     * Write a stock level.
     *
     * @param sku which SKU
     * @param onHand the level to write
     * @param reserved how much of it is spoken for
     * @param expectedVersion the version the row had when the decision was made, or
     *     {@link StockItem#ABSENT} to mean the row must not exist yet
     */
    record PutStock(Sku sku, long onHand, long reserved, long expectedVersion) implements Mutation {

        public PutStock {
            if (sku == null) {
                throw new IllegalArgumentException("sku must not be null");
            }
            if (onHand < 0 || reserved < 0 || reserved > onHand) {
                throw new IllegalArgumentException(
                        "refusing to write an impossible level for " + sku + ": onHand=" + onHand + " reserved=" + reserved);
            }
        }

        /**
         * Whether this mutation creates the row rather than updating it.
         *
         * @return true when the expected version is {@link StockItem#ABSENT}
         */
        public boolean isInsert() {
            return expectedVersion == StockItem.ABSENT;
        }
    }

    /**
     * Create a reservation. Fails if the id is already taken.
     *
     * @param reservation the reservation to write, at version 0
     */
    record InsertReservation(Reservation reservation) implements Mutation {

        public InsertReservation {
            if (reservation == null) {
                throw new IllegalArgumentException("reservation must not be null");
            }
            if (reservation.version() != 0) {
                throw new IllegalArgumentException(
                        "a new reservation is written at version 0, got " + reservation.version());
            }
        }
    }

    /**
     * Move a reservation to a terminal state.
     *
     * @param reservationId which reservation
     * @param state the state to move to, never {@link ReservationState#HELD}
     * @param expectedVersion the version the row had when the decision was made
     */
    record SetReservationState(ReservationId reservationId, ReservationState state, long expectedVersion)
            implements Mutation {

        public SetReservationState {
            if (reservationId == null) {
                throw new IllegalArgumentException("reservation id must not be null");
            }
            if (state == null || !state.isTerminal()) {
                throw new IllegalArgumentException(
                        "a reservation only ever moves to a terminal state, got " + state);
            }
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expected version must not be negative, got " + expectedVersion);
            }
        }
    }
}
