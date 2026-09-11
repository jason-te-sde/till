package io.till.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a command needs to be decided, read at one instant.
 *
 * <p>The snapshot is the kernel's entire view of the world. It is assembled by a {@link Ledger} and
 * must be consistent — one transaction, or one read of an in-memory map under a lock — because a
 * decision made against rows read at two different moments can be wrong in a way no version check
 * will catch.
 *
 * <p>A SKU the command mentions must be present, even if the ledger has no row for it: the entry is
 * then {@link StockItem#empty} at {@link StockItem#ABSENT}, and the kernel answers
 * {@link RejectionCode#UNKNOWN_SKU}. A SKU that is missing from the map altogether is an adapter bug
 * and raises {@link IncompleteSnapshotException}. The distinction is the point: "you have never
 * stocked this" is an answer, "I did not load it" is not.
 *
 * @param stock one entry per SKU in scope
 * @param reservation the reservation the command names, if it exists
 * @param recordedOutcome what this command's idempotency key was used for before, if anything
 * @param reclaimable held reservations, touching the SKUs above, whose deadline has passed
 */
public record Snapshot(
        Map<Sku, StockItem> stock,
        Optional<Reservation> reservation,
        Optional<OutcomeRecord> recordedOutcome,
        List<Reservation> reclaimable) {

    public Snapshot {
        stock = Map.copyOf(stock);
        reclaimable = List.copyOf(reclaimable);
        if (reservation == null || recordedOutcome == null) {
            throw new IllegalArgumentException("snapshot optionals must not be null");
        }
    }

    /**
     * An empty snapshot, for a ledger that found nothing.
     *
     * @return a snapshot with no stock, no reservation and no record
     */
    public static Snapshot empty() {
        return new Snapshot(Map.of(), Optional.empty(), Optional.empty(), List.of());
    }

    /**
     * Starts building a snapshot.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The level of a SKU the command is about.
     *
     * @param sku the SKU
     * @return its level, possibly {@link StockItem#empty}
     * @throws IncompleteSnapshotException if the ledger did not load it
     */
    public StockItem require(Sku sku) {
        StockItem item = stock.get(sku);
        if (item == null) {
            throw new IncompleteSnapshotException(
                    "snapshot has no entry for sku " + sku + "; the ledger must load every sku in scope, "
                            + "using StockItem.empty(sku) for one that has no row");
        }
        return item;
    }

    /** Assembles a snapshot; every field defaults to absent or empty. */
    public static final class Builder {

        private final Map<Sku, StockItem> stock = new LinkedHashMap<>();
        private final List<Reservation> reclaimable = new ArrayList<>();
        private Reservation reservation;
        private OutcomeRecord recordedOutcome;

        private Builder() {}

        /**
         * Adds a stock level.
         *
         * @param item the level
         * @return this builder
         */
        public Builder stock(StockItem item) {
            stock.put(item.sku(), item);
            return this;
        }

        /**
         * Adds a stock level from its parts.
         *
         * @param sku which SKU
         * @param onHand units physically held
         * @param reserved units spoken for
         * @param version the row's version
         * @return this builder
         */
        public Builder stock(Sku sku, long onHand, long reserved, long version) {
            return stock(new StockItem(sku, onHand, reserved, version));
        }

        /**
         * Records that a SKU in scope has no row.
         *
         * @param sku which SKU
         * @return this builder
         */
        public Builder absent(Sku sku) {
            return stock(StockItem.empty(sku));
        }

        /**
         * Sets the reservation the command names.
         *
         * @param value the reservation, or null if it does not exist
         * @return this builder
         */
        public Builder reservation(Reservation value) {
            this.reservation = value;
            return this;
        }

        /**
         * Sets what the command's idempotency key was used for before.
         *
         * @param value the record, or null if the key is new
         * @return this builder
         */
        public Builder recordedOutcome(OutcomeRecord value) {
            this.recordedOutcome = value;
            return this;
        }

        /**
         * Adds a held reservation whose deadline has passed.
         *
         * @param value the reservation
         * @return this builder
         */
        public Builder reclaimable(Reservation value) {
            reclaimable.add(value);
            return this;
        }

        /**
         * Adds held reservations whose deadlines have passed.
         *
         * @param values the reservations
         * @return this builder
         */
        public Builder reclaimable(List<Reservation> values) {
            reclaimable.addAll(values);
            return this;
        }

        /**
         * Builds the snapshot.
         *
         * @return the snapshot
         */
        public Snapshot build() {
            return new Snapshot(
                    stock, Optional.ofNullable(reservation), Optional.ofNullable(recordedOutcome), reclaimable);
        }
    }
}
