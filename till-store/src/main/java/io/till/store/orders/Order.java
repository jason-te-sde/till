package io.till.store.orders;

import io.till.core.ReservationId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A customer's purchase, as the store records it.
 *
 * <p>Linked one-to-one with a reservation in the ledger, and never deciding anything the reservation
 * decides. The order records what the customer was charged and what they were told; whether the stock
 * existed, whether the hold ran out and whether it was already paid for are the ledger's answers, and
 * the order's status follows them.
 *
 * @param id the store's identifier for it
 * @param customer the OIDC subject of whoever placed it
 * @param idemKey the customer's key for the checkout attempt that placed it
 * @param reservationId the ledger's hold
 * @param status where it stands, as last recorded
 * @param totalCents what it costs, in minor units, fixed when it was placed
 * @param currency ISO 4217
 * @param createdAt when it was placed
 * @param expiresAt when the hold stops counting unless paid for
 * @param closedAt when it left {@link Status#PENDING}, or null while it has not
 * @param lines what it is for
 */
public record Order(
        UUID id,
        String customer,
        String idemKey,
        ReservationId reservationId,
        Status status,
        long totalCents,
        String currency,
        Instant createdAt,
        Instant expiresAt,
        Instant closedAt,
        List<Line> lines) {

    public Order {
        lines = List.copyOf(lines);
    }

    /**
     * Where the order stands <i>now</i>, which is not always what was last written.
     *
     * <p>An order that is still recorded as pending but whose deadline has passed <b>is</b> expired,
     * whether or not the ledger's expiry event has arrived yet. The same rule the ledger itself runs
     * by: the deadline is the truth, and the write that records it is a formality that may be late.
     * Showing "awaiting payment" with a countdown at zero would be a page arguing with its own clock.
     *
     * @param now the current instant
     * @return the status a customer should see
     */
    public Status effectiveStatus(Instant now) {
        if (status == Status.PENDING && !now.isBefore(expiresAt)) {
            return Status.EXPIRED;
        }
        return status;
    }

    /** Where an order stands. */
    public enum Status {
        /** Stock is held; waiting for payment. */
        PENDING,
        /** Paid for; the stock has left. */
        PAID,
        /** Given back by the customer. */
        CANCELLED,
        /** The hold ran out before payment. */
        EXPIRED
    }

    /**
     * One game on an order, at the price it was bought at.
     *
     * @param sku which game
     * @param title its name when bought — kept, because the catalogue may rename it
     * @param unitPriceCents its price when bought — kept, because the catalogue may reprice it
     * @param quantity how many copies
     */
    public record Line(String sku, String title, long unitPriceCents, int quantity) {

        /**
         * @return price times quantity
         */
        public long subtotalCents() {
            return Math.multiplyExact(unitPriceCents, quantity);
        }
    }
}
