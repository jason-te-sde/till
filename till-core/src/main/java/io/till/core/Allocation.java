package io.till.core;

import java.util.Comparator;

/**
 * Where some of a hold's units came from: so many of one SKU, from one of its shards.
 *
 * <p>A hold's {@linkplain Reservation#lines() lines} say what it holds; its allocations say where. A
 * line is usually one allocation, from the shard its reservation id pointed at, and several only when
 * no one shard had all of it (ADR 9). Committing, releasing and expiring a hold return each allocation
 * to the shard it came from.
 *
 * @param sku which SKU
 * @param shard which of its shards
 * @param quantity how many units, at least 1
 */
public record Allocation(Sku sku, int shard, long quantity) {

    /** SKU order, then shard order. */
    public static final Comparator<Allocation> ORDER =
            Comparator.comparing(Allocation::sku).thenComparingInt(Allocation::shard);

    public Allocation {
        if (sku == null) {
            throw new IllegalArgumentException("allocation sku must not be null");
        }
        if (shard < 0) {
            throw new IllegalArgumentException("shard must not be negative, got " + shard + " for " + sku);
        }
        if (quantity < 1 || quantity > Line.MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "allocation quantity must be between 1 and " + Line.MAX_QUANTITY + ", got " + quantity + " for " + sku);
        }
    }

    @Override
    public String toString() {
        return sku + "#" + shard + "x" + quantity;
    }
}
