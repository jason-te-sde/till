package io.till.core;

import java.time.Instant;
import java.util.List;

/**
 * What a command did, as the caller sees it.
 *
 * <p>An outcome is the unit of idempotency. It is what gets recorded under the command's key and
 * what a replay of that key returns, which is why it holds every value the caller was told and no
 * value the caller was not: a recorded outcome that referred to internal state could not be replayed
 * faithfully once that state moved on.
 *
 * <p>A {@link Rejected} outcome is still an outcome. The command was considered and refused, that
 * answer is recorded, and asking again with the same key gives the same refusal. Refusing to record
 * rejections would mean a client that retried a timed-out request could be told "no stock" the first
 * time and "created" the second, having been charged for one order and shipped two.
 */
public sealed interface Outcome {

    /**
     * Whether the command was carried out.
     *
     * @return false only for {@link Rejected}
     */
    default boolean ok() {
        return !(this instanceof Rejected);
    }

    /**
     * Stock was set aside.
     *
     * @param id the reservation that now holds it
     * @param lines what is held, sorted by SKU
     * @param expiresAt when the hold stops counting unless committed
     */
    record Reserved(ReservationId id, List<Line> lines, Instant expiresAt) implements Outcome {
        public Reserved {
            lines = Reservation.canonical(lines);
        }
    }

    /**
     * A hold turned into a sale: on-hand and reserved both went down.
     *
     * @param id the reservation
     * @param at when it happened
     */
    record Committed(ReservationId id, Instant at) implements Outcome {}

    /**
     * A hold was given up, or had already been given up. Reserved went down; on-hand did not.
     *
     * @param id the reservation
     * @param at when it happened, or when the caller asked if it had already happened
     */
    record Released(ReservationId id, Instant at) implements Outcome {}

    /**
     * On-hand stock was changed directly.
     *
     * @param sku which SKU
     * @param onHand the level afterwards
     * @param reserved how much of it is spoken for
     */
    record Adjusted(Sku sku, long onHand, long reserved) implements Outcome {

        /**
         * Units a new reservation could take.
         *
         * @return {@code onHand - reserved}
         */
        public long available() {
            return onHand - reserved;
        }
    }

    /**
     * Expired holds were written off.
     *
     * @param reclaimed how many reservations moved to {@link ReservationState#EXPIRED}
     */
    record Swept(int reclaimed) implements Outcome {}

    /**
     * The command was refused, and nothing changed.
     *
     * @param code why
     * @param detail a sentence naming the specific thing, for a log line or an error body
     * @param shortfalls per-SKU detail for {@link RejectionCode#INSUFFICIENT_STOCK}, empty otherwise
     */
    record Rejected(RejectionCode code, String detail, List<Shortfall> shortfalls) implements Outcome {

        public Rejected {
            if (code == null) {
                throw new IllegalArgumentException("rejection code must not be null");
            }
            if (detail == null) {
                throw new IllegalArgumentException("rejection detail must not be null");
            }
            shortfalls = List.copyOf(shortfalls);
        }

        /**
         * A rejection with no per-SKU detail.
         *
         * @param code why
         * @param detail a sentence naming the specific thing
         * @return the rejection
         */
        public static Rejected of(RejectionCode code, String detail) {
            return new Rejected(code, detail, List.of());
        }
    }

    /**
     * How far short one SKU fell.
     *
     * <p>Reported for every line that could not be satisfied, not only the first, because a caller
     * deciding whether to offer a partial basket needs all of them and a second round trip per SKU
     * is a second chance for the numbers to move.
     *
     * @param sku which SKU
     * @param requested units the command asked for
     * @param available units that could have been taken
     */
    record Shortfall(Sku sku, long requested, long available) {}
}
