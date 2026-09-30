package io.till.core;

import java.util.List;

/**
 * The stock level of one SKU: what is physically there, and how much of it is spoken for.
 *
 * <p>What the ledger reports, and the sum of the rows it keeps: a SKU's stock is kept in one or more
 * {@link StockShard}s (ADR 9), and this is them added up.
 *
 * <p>Three numbers, two of them stored:
 *
 * <pre>
 *   onHand      units the warehouse holds
 *   reserved    units held by open reservations
 *   available   onHand - reserved, what a new reservation may take
 * </pre>
 *
 * <p>A commit lowers both {@code onHand} and {@code reserved} — the goods left and the hold on them
 * went with them. A release or an expiry lowers only {@code reserved}. Nothing else moves either
 * number except an explicit {@link Command.Adjust}.
 *
 * <p>{@code reserved > onHand} is refused by the constructor rather than reported later. Every way
 * to reach that state is a bug in the kernel, and a type that cannot hold the broken value fails at
 * the line that computed it instead of three commands later, where the arithmetic looks fine.
 *
 * @param sku which SKU
 * @param onHand units physically held, never negative
 * @param reserved units spoken for, never negative and never more than {@code onHand}
 * @param version optimistic concurrency token, or {@link #ABSENT} when the row does not exist yet; for
 *     a SKU in several shards, the sum of theirs, which moves on every write to any of them
 * @param shards how many rows the stock is kept in: 0 for a SKU with none, 1 for one never split
 */
public record StockItem(Sku sku, long onHand, long reserved, long version, int shards) {

    /**
     * The version of a SKU that has no row yet.
     *
     * <p>A {@link Mutation.PutStock} carrying this as its expected version is an insert, and it must
     * fail if the row turned out to exist. That is what makes two concurrent first-time adjustments
     * of the same SKU resolve to one insert and one retry rather than to one of them being lost.
     */
    public static final long ABSENT = -1L;

    public StockItem {
        if (sku == null) {
            throw new IllegalArgumentException("stock sku must not be null");
        }
        if (onHand < 0) {
            throw new IllegalArgumentException("on-hand must not be negative, got " + onHand + " for " + sku);
        }
        if (reserved < 0) {
            throw new IllegalArgumentException("reserved must not be negative, got " + reserved + " for " + sku);
        }
        if (reserved > onHand) {
            throw new IllegalArgumentException(
                    "reserved must not exceed on-hand for " + sku + ": reserved=" + reserved + " onHand=" + onHand);
        }
        if (version < ABSENT) {
            throw new IllegalArgumentException("version must not be below " + ABSENT + ", got " + version);
        }
        if (shards < 0) {
            throw new IllegalArgumentException("shards must not be negative, got " + shards + " for " + sku);
        }
    }

    /**
     * A SKU kept in one row, as every SKU is until it is split.
     *
     * @param sku which SKU
     * @param onHand units physically held
     * @param reserved units spoken for
     * @param version the row's version, or {@link #ABSENT}
     */
    public StockItem(Sku sku, long onHand, long reserved, long version) {
        this(sku, onHand, reserved, version, version == ABSENT ? 0 : 1);
    }

    /**
     * A SKU that exists with nothing in it.
     *
     * @param sku which SKU
     * @return an absent item at zero
     */
    public static StockItem empty(Sku sku) {
        return new StockItem(sku, 0, 0, ABSENT, 0);
    }

    /**
     * A SKU's level from its shards.
     *
     * @param sku which SKU
     * @param shards its shards, all of them; none for a SKU with no row
     * @return their sum, or {@link #empty} if there are none
     */
    public static StockItem of(Sku sku, List<StockShard> shards) {
        if (shards.isEmpty()) {
            return empty(sku);
        }
        long onHand = 0;
        long reserved = 0;
        long version = 0;
        for (StockShard shard : shards) {
            if (!shard.sku().equals(sku)) {
                throw new IllegalArgumentException("shard " + shard + " is not a shard of " + sku);
            }
            onHand += shard.onHand();
            reserved += shard.reserved();
            version += Math.max(0, shard.version());
        }
        return new StockItem(sku, onHand, reserved, version, shards.size());
    }

    /**
     * Units a new reservation may take.
     *
     * @return {@code onHand - reserved}, never negative
     */
    public long available() {
        return onHand - reserved;
    }

    /**
     * Whether this SKU has a row in the ledger.
     *
     * @return false if the SKU has never been adjusted into existence
     */
    public boolean exists() {
        return version != ABSENT;
    }

    /**
     * This item with {@code delta} added to {@code reserved}.
     *
     * @param delta units to add, negative to release
     * @return the new level, at the same version
     */
    public StockItem withReservedDelta(long delta) {
        return new StockItem(sku, onHand, reserved + delta, version, shards);
    }

    /**
     * This item with {@code delta} added to both {@code onHand} and {@code reserved}, which is what
     * committing a hold does.
     *
     * @param delta units to add to both, negative on commit
     * @return the new level, at the same version
     */
    public StockItem withCommitDelta(long delta) {
        return new StockItem(sku, onHand + delta, reserved + delta, version, shards);
    }

    /**
     * This item with {@code delta} added to {@code onHand}.
     *
     * @param delta units to add, negative to write stock off
     * @return the new level, at the same version
     */
    public StockItem withOnHandDelta(long delta) {
        return new StockItem(sku, onHand + delta, reserved, version, shards);
    }

    @Override
    public String toString() {
        return sku + "[onHand=" + onHand + " reserved=" + reserved + " available=" + available() + " v" + version
                + (shards > 1 ? " in " + shards + " shards" : "") + "]";
    }
}
