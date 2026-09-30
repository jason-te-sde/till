package io.till.core;

import java.util.Comparator;

/**
 * One row of a SKU's stock: the slice of it one shard owns.
 *
 * <p>A SKU's stock lives in one or more shards (ADR 9). Each is a level in its own right — what it has
 * on hand, how much of that its holds have spoken for — and each carries the version that optimistic
 * concurrency checks, which is the point of having several: two commands on the same SKU conflict only
 * if they write the same shard. The SKU's {@link StockItem} is the sum of its shards.
 *
 * <p>{@code reserved > onHand} is refused by the constructor, for a shard exactly as for a SKU: a hold
 * takes its units from a shard that has them, so a shard that does not is a kernel bug, and it fails at
 * the line that computed it.
 *
 * @param sku which SKU
 * @param index which of its shards, from 0
 * @param onHand units this shard holds, never negative
 * @param reserved units of those spoken for, never negative and never more than {@code onHand}
 * @param version optimistic concurrency token, or {@link StockItem#ABSENT} for a shard not yet written
 */
public record StockShard(Sku sku, int index, long onHand, long reserved, long version) {

    /** SKU order, then shard order: the order a decision writes rows in, so that two never deadlock. */
    public static final Comparator<StockShard> ORDER =
            Comparator.comparing(StockShard::sku).thenComparingInt(StockShard::index);

    public StockShard {
        if (sku == null) {
            throw new IllegalArgumentException("stock sku must not be null");
        }
        if (index < 0) {
            throw new IllegalArgumentException("shard index must not be negative, got " + index + " for " + sku);
        }
        if (onHand < 0) {
            throw new IllegalArgumentException(
                    "on-hand must not be negative, got " + onHand + " for " + sku + " shard " + index);
        }
        if (reserved < 0) {
            throw new IllegalArgumentException(
                    "reserved must not be negative, got " + reserved + " for " + sku + " shard " + index);
        }
        if (reserved > onHand) {
            throw new IllegalArgumentException("reserved must not exceed on-hand for " + sku + " shard " + index
                    + ": reserved=" + reserved + " onHand=" + onHand);
        }
        if (version < StockItem.ABSENT) {
            throw new IllegalArgumentException("version must not be below " + StockItem.ABSENT + ", got " + version);
        }
    }

    /**
     * A shard that has not been written yet.
     *
     * @param sku which SKU
     * @param index which shard
     * @return an absent shard at zero
     */
    public static StockShard empty(Sku sku, int index) {
        return new StockShard(sku, index, 0, 0, StockItem.ABSENT);
    }

    /**
     * Units a new hold may take from this shard.
     *
     * @return {@code onHand - reserved}, never negative
     */
    public long available() {
        return onHand - reserved;
    }

    /**
     * Whether this shard has a row in the ledger.
     *
     * @return false for a shard that a decision is about to create
     */
    public boolean exists() {
        return version != StockItem.ABSENT;
    }

    /**
     * This shard with {@code delta} added to {@code reserved}.
     *
     * @param delta units to add, negative to release
     * @return the new level, at the same version
     */
    public StockShard withReservedDelta(long delta) {
        return new StockShard(sku, index, onHand, reserved + delta, version);
    }

    /**
     * This shard with {@code delta} added to both {@code onHand} and {@code reserved}, which is what
     * committing a hold does.
     *
     * @param delta units to add to both, negative on commit
     * @return the new level, at the same version
     */
    public StockShard withCommitDelta(long delta) {
        return new StockShard(sku, index, onHand + delta, reserved + delta, version);
    }

    /**
     * This shard with {@code delta} added to {@code onHand}.
     *
     * @param delta units to add, negative to take them away
     * @return the new level, at the same version
     */
    public StockShard withOnHandDelta(long delta) {
        return new StockShard(sku, index, onHand + delta, reserved, version);
    }

    @Override
    public String toString() {
        return sku + "#" + index + "[onHand=" + onHand + " reserved=" + reserved + " v" + version + "]";
    }
}
