package io.till.core;

/**
 * One SKU and how many of it a command is about.
 *
 * @param sku what is being held
 * @param quantity how many, at least 1 and at most {@link #MAX_QUANTITY}
 */
public record Line(Sku sku, long quantity) {

    /**
     * Largest quantity a single line may carry.
     *
     * <p>A billion is far above any real order and far below the point where summing lines into a
     * stock level could overflow a {@code long}. The cap exists so that arithmetic in the kernel
     * needs no overflow checks of its own: a stock level is a sum of at most a few million lines,
     * each at most this, which cannot reach {@link Long#MAX_VALUE}.
     */
    public static final long MAX_QUANTITY = 1_000_000_000L;

    public Line {
        if (sku == null) {
            throw new IllegalArgumentException("line sku must not be null");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException(
                    "line quantity must be at least 1, got " + quantity + " for sku " + sku);
        }
        if (quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "line quantity must be at most " + MAX_QUANTITY + ", got " + quantity);
        }
    }

    /**
     * Convenience factory.
     *
     * @param sku what is being held
     * @param quantity how many
     * @return the line
     */
    public static Line of(String sku, long quantity) {
        return new Line(Sku.of(sku), quantity);
    }

    @Override
    public String toString() {
        return sku + "x" + quantity;
    }
}
