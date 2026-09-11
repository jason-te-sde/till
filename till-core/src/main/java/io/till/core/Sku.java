package io.till.core;

/**
 * A stock keeping unit: the thing a quantity is counted in.
 *
 * <p>till holds no opinion about what a SKU means. It never joins to a product catalogue, so the
 * value can be a database id, a barcode, or a seat number, and a caller with a catalogue of its own
 * does not have to mirror it here.
 *
 * @param value the identifier, at most 64 characters from {@code A-Za-z0-9._:@=+/-}
 */
public record Sku(String value) implements Comparable<Sku> {

    /** Longest accepted SKU, in characters. */
    public static final int MAX_LENGTH = 64;

    public Sku {
        Ids.check("sku", value, MAX_LENGTH);
    }

    /**
     * Equivalent to the constructor, for call sites where it reads better.
     *
     * @param value the identifier
     * @return the SKU
     */
    public static Sku of(String value) {
        return new Sku(value);
    }

    @Override
    public int compareTo(Sku other) {
        return value.compareTo(other.value);
    }

    /**
     * Returns the identifier itself, with no wrapper text.
     *
     * <p>These appear in log lines and error messages constantly, and {@code Sku[value=widget]} is
     * noise in every one of them.
     */
    @Override
    public String toString() {
        return value;
    }
}
