package io.till.core;

import java.util.ArrayList;
import java.util.Collections;
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
 * then an empty list of shards, and the kernel answers {@link RejectionCode#UNKNOWN_SKU}. A SKU that
 * is missing from the map altogether is an adapter bug and raises {@link IncompleteSnapshotException}.
 * The distinction is the point: "you have never stocked this" is an answer, "I did not load it" is
 * not.
 *
 * <p>A SKU present is present with <b>every</b> shard it has, numbered from 0 without a gap (ADR 9):
 * a hold is refused only when the SKU as a whole is short, and only a snapshot with all of it can say
 * so.
 *
 * @param stock one entry per SKU in scope: its shards, in index order
 * @param reservation the reservation the command names, if it exists
 * @param recordedOutcome what this command's idempotency key was used for before, if anything
 * @param reclaimable held reservations, touching the SKUs above, whose deadline has passed
 */
public record Snapshot(
        Map<Sku, List<StockShard>> stock,
        Optional<Reservation> reservation,
        Optional<OutcomeRecord> recordedOutcome,
        List<Reservation> reclaimable) {

    public Snapshot {
        Map<Sku, List<StockShard>> copy = new LinkedHashMap<>();
        stock.forEach((sku, shards) -> copy.put(sku, inOrder(sku, shards)));
        stock = Collections.unmodifiableMap(copy);
        reclaimable = List.copyOf(reclaimable);
        if (reservation == null || recordedOutcome == null) {
            throw new IllegalArgumentException("snapshot optionals must not be null");
        }
    }

    /** A SKU's shards in index order, refusing a gap: a snapshot with part of a SKU is not one. */
    private static List<StockShard> inOrder(Sku sku, List<StockShard> shards) {
        List<StockShard> sorted = new ArrayList<>(shards);
        sorted.sort(StockShard.ORDER);
        for (int i = 0; i < sorted.size(); i++) {
            StockShard shard = sorted.get(i);
            if (!shard.sku().equals(sku) || shard.index() != i || !shard.exists()) {
                throw new IncompleteSnapshotException(
                        "the shards of " + sku + " must be every stored shard from 0, got " + shards);
            }
        }
        return List.copyOf(sorted);
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
     * The shards of a SKU the command is about.
     *
     * @param sku the SKU
     * @return every shard it has, in index order; none for a SKU that has no row
     * @throws IncompleteSnapshotException if the ledger did not load it
     */
    public List<StockShard> shards(Sku sku) {
        List<StockShard> shards = stock.get(sku);
        if (shards == null) {
            throw new IncompleteSnapshotException(
                    "snapshot has no entry for sku " + sku + "; the ledger must load every sku in scope, "
                            + "with no shards for one that has no row");
        }
        return shards;
    }

    /**
     * The level of a SKU the command is about: its shards, added up.
     *
     * @param sku the SKU
     * @return its level, possibly {@link StockItem#empty}
     * @throws IncompleteSnapshotException if the ledger did not load it
     */
    public StockItem require(Sku sku) {
        return StockItem.of(sku, shards(sku));
    }

    /** Assembles a snapshot; every field defaults to absent or empty. */
    public static final class Builder {

        private final Map<Sku, List<StockShard>> stock = new LinkedHashMap<>();
        private final List<Reservation> reclaimable = new ArrayList<>();
        private Reservation reservation;
        private OutcomeRecord recordedOutcome;

        private Builder() {}

        /**
         * Adds a SKU kept in one row.
         *
         * @param item the level; at {@link StockItem#ABSENT}, a SKU in scope with no row
         * @return this builder
         */
        public Builder stock(StockItem item) {
            if (item.shards() > 1) {
                throw new IllegalArgumentException(item + " is kept in shards; add them one by one");
            }
            List<StockShard> rows = new ArrayList<>();
            if (item.exists()) {
                rows.add(new StockShard(item.sku(), 0, item.onHand(), item.reserved(), item.version()));
            }
            stock.put(item.sku(), rows);
            return this;
        }

        /**
         * Adds a SKU kept in one row, from its parts.
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
         * Adds one shard of a SKU's stock.
         *
         * @param shard the shard
         * @return this builder
         */
        public Builder shard(StockShard shard) {
            stock.computeIfAbsent(shard.sku(), ignored -> new ArrayList<>()).add(shard);
            return this;
        }

        /**
         * Records that a SKU in scope has no row.
         *
         * @param sku which SKU
         * @return this builder
         */
        public Builder absent(Sku sku) {
            stock.computeIfAbsent(sku, ignored -> new ArrayList<>());
            return this;
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
