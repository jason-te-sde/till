package io.till.core;

import java.util.UUID;

/**
 * The identity of one hold on stock.
 *
 * <p>Supplied by the caller, never invented by the kernel. That is what keeps {@link Kernel} a pure
 * function: a decision that generated an identifier would depend on a random source, and a run would
 * stop being reproducible from its seed. {@link #random()} exists for callers that have no
 * identifier of their own, and the kernel never calls it.
 *
 * @param value the identifier, at most 64 characters from {@code A-Za-z0-9._:@=+/-}
 */
public record ReservationId(String value) implements Comparable<ReservationId> {

    /** Longest accepted identifier, in characters. */
    public static final int MAX_LENGTH = 64;

    public ReservationId {
        Ids.check("reservation id", value, MAX_LENGTH);
    }

    /**
     * Equivalent to the constructor, for call sites where it reads better.
     *
     * @param value the identifier
     * @return the reservation id
     */
    public static ReservationId of(String value) {
        return new ReservationId(value);
    }

    /**
     * A fresh random identifier, for callers that do not have one of their own.
     *
     * <p>Never called from {@link Kernel}. A caller that wants reproducible runs should generate
     * identifiers from its own seeded source instead.
     *
     * @return a type 4 UUID as a reservation id
     */
    public static ReservationId random() {
        return new ReservationId(UUID.randomUUID().toString());
    }

    @Override
    public int compareTo(ReservationId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
