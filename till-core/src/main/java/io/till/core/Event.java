package io.till.core;

import java.time.Instant;
import java.util.List;

/**
 * Something that happened, for whoever else needs to know.
 *
 * <p>Events are written in the same transaction as the state change that produced them — the
 * transactional outbox — so there is no window in which stock moved and nobody downstream will hear
 * about it, and none in which an event describes a change that was rolled back. A publisher reads
 * them afterwards and delivers at least once.
 *
 * <p>At least once, not exactly once, which is why every event has a {@link #dedupeKey()} that is a
 * function of what happened rather than of when it was published. A consumer that has seen the key
 * can drop the duplicate. The keys are stable across republishing, across a crash mid-publish, and
 * across the same event being read by two publisher instances.
 */
public sealed interface Event {

    /**
     * A name for this event that is the same every time it is produced.
     *
     * <p>Derived from the identity of the thing that changed and the kind of change, both of which
     * can happen at most once: a reservation is created once, committed at most once, and released
     * at most once. An adjustment has no such identity of its own, so it borrows its command's
     * idempotency key, which the ledger already guarantees is unique.
     *
     * @return a key a consumer can deduplicate on
     */
    String dedupeKey();

    /**
     * When the change happened, as decided by the kernel.
     *
     * <p>This is the instant the command was evaluated at, not the instant the row was inserted or
     * the message was published. Two events from one decision carry the same value.
     *
     * @return the decision instant
     */
    Instant occurredAt();

    /**
     * Stock was set aside.
     *
     * @param reservationId the new hold
     * @param lines what it holds
     * @param expiresAt when the hold stops counting
     * @param occurredAt the decision instant
     */
    record StockReserved(ReservationId reservationId, List<Line> lines, Instant expiresAt, Instant occurredAt)
            implements Event {

        public StockReserved {
            lines = Reservation.canonical(lines);
        }

        @Override
        public String dedupeKey() {
            return "reserved:" + reservationId;
        }
    }

    /**
     * A hold became a sale.
     *
     * @param reservationId the hold
     * @param lines what left
     * @param occurredAt the decision instant
     */
    record StockCommitted(ReservationId reservationId, List<Line> lines, Instant occurredAt) implements Event {

        public StockCommitted {
            lines = Reservation.canonical(lines);
        }

        @Override
        public String dedupeKey() {
            return "committed:" + reservationId;
        }
    }

    /**
     * A hold was given up on purpose.
     *
     * @param reservationId the hold
     * @param lines what went back to available
     * @param occurredAt the decision instant
     */
    record StockReleased(ReservationId reservationId, List<Line> lines, Instant occurredAt) implements Event {

        public StockReleased {
            lines = Reservation.canonical(lines);
        }

        @Override
        public String dedupeKey() {
            return "released:" + reservationId;
        }
    }

    /**
     * A hold ran out of time.
     *
     * <p>Emitted by whichever command noticed, which may be a sweep or may be an unrelated
     * reservation that needed the stock. A consumer cannot tell the two apart and does not need to.
     *
     * @param reservationId the hold
     * @param lines what went back to available
     * @param occurredAt the decision instant
     */
    record StockExpired(ReservationId reservationId, List<Line> lines, Instant occurredAt) implements Event {

        public StockExpired {
            lines = Reservation.canonical(lines);
        }

        @Override
        public String dedupeKey() {
            return "expired:" + reservationId;
        }
    }

    /**
     * On-hand stock was changed directly.
     *
     * @param key the idempotency key of the adjustment, which is what makes this event unique
     * @param sku which SKU
     * @param delta how much was added or removed
     * @param onHand the level afterwards
     * @param reserved how much of it is spoken for
     * @param occurredAt the decision instant
     */
    record StockAdjusted(
            IdempotencyKey key, Sku sku, long delta, long onHand, long reserved, Instant occurredAt)
            implements Event {

        /** The prefix every adjustment's deduplication key starts with. */
        public static final String DEDUPE_PREFIX = "adjusted:";

        @Override
        public String dedupeKey() {
            return dedupeKeyFor(key);
        }

        /**
         * The deduplication key an adjustment under this idempotency key would have.
         *
         * <p>Exposed because retention has to ask. An adjustment has no identity of its own — no
         * reservation, no row of its own — so its key is borrowed from the command's, and that key
         * is unique only for as long as the ledger remembers it. Forgetting the idempotency record
         * while this event is still in the outbox would let a later, genuinely different adjustment
         * produce the same key.
         *
         * @param key the adjustment's idempotency key
         * @return the deduplication key
         */
        public static String dedupeKeyFor(IdempotencyKey key) {
            return DEDUPE_PREFIX + key;
        }
    }
}
