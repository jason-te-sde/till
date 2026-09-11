package io.till.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One hold on stock, and everything the kernel needs to decide what may happen to it next.
 *
 * <p>Lines are canonical: sorted by SKU, with duplicates refused. Two callers that ask for the same
 * two SKUs in opposite orders produce the same reservation and, more importantly, the same
 * {@linkplain Command#fingerprint() fingerprint}, so a retry that happened to reorder its body is
 * still recognised as the same request.
 *
 * @param id the caller's identifier for this hold
 * @param key the idempotency key of the command that created it
 * @param lines what is held, sorted by SKU, at least one, no SKU twice
 * @param state the stored state, which {@link #effectiveState(Instant)} may override
 * @param createdAt when the hold was taken
 * @param expiresAt when the hold stops counting, whether or not anything has noticed
 * @param version optimistic concurrency token, 0 when first written
 */
public record Reservation(
        ReservationId id,
        IdempotencyKey key,
        List<Line> lines,
        ReservationState state,
        Instant createdAt,
        Instant expiresAt,
        long version) {

    public Reservation {
        if (id == null) {
            throw new IllegalArgumentException("reservation id must not be null");
        }
        if (key == null) {
            throw new IllegalArgumentException("reservation idempotency key must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("reservation state must not be null");
        }
        if (createdAt == null || expiresAt == null) {
            throw new IllegalArgumentException("reservation timestamps must not be null");
        }
        if (expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "reservation " + id + " expires (" + expiresAt + ") before it was created (" + createdAt + ")");
        }
        if (version < 0) {
            throw new IllegalArgumentException("reservation version must not be negative, got " + version);
        }
        lines = canonical(lines);
    }

    /**
     * Sorts lines by SKU and refuses duplicates.
     *
     * @param lines the lines as supplied
     * @return an unmodifiable, sorted copy
     * @throws IllegalArgumentException if empty or if a SKU appears twice
     */
    static List<Line> canonical(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        Set<Sku> seen = new HashSet<>();
        for (Line line : lines) {
            if (!seen.add(line.sku())) {
                // Merging them would be friendlier and wrong: a body with the same SKU twice is a
                // client that built its request in two places, and silently adding the quantities
                // turns that bug into a larger order rather than into an error.
                throw new IllegalArgumentException("sku " + line.sku() + " appears more than once");
            }
        }
        List<Line> copy = new ArrayList<>(lines);
        copy.sort(Comparator.comparing(Line::sku));
        return List.copyOf(copy);
    }

    /**
     * The state this reservation is really in at {@code now}.
     *
     * <p>A hold whose deadline has passed is {@link ReservationState#EXPIRED} even though the stored
     * state still says {@code HELD}, because nothing has written the change down yet. Reading the
     * stored value directly is the bug this method exists to prevent: it would make the answer to
     * "may this commit?" depend on whether a background sweep happened to have run, which is not a
     * property a caller can reason about and not one a test can reproduce.
     *
     * <p>The sweep is therefore an optimisation — it returns stock to {@code available} sooner —
     * and never a correctness requirement.
     *
     * @param now the instant to judge against
     * @return {@link ReservationState#EXPIRED} for a held reservation past its deadline, otherwise
     *     the stored state
     */
    public ReservationState effectiveState(Instant now) {
        if (state == ReservationState.HELD && !now.isBefore(expiresAt)) {
            return ReservationState.EXPIRED;
        }
        return state;
    }

    /**
     * Whether this is a hold that has run out of time but has not been written off yet.
     *
     * @param now the instant to judge against
     * @return true if the stored state is {@code HELD} and the deadline has passed
     */
    public boolean isReclaimableAt(Instant now) {
        return state == ReservationState.HELD && !now.isBefore(expiresAt);
    }

    /**
     * The SKUs this reservation touches.
     *
     * @return one SKU per line, in line order
     */
    public List<Sku> skus() {
        return lines.stream().map(Line::sku).toList();
    }

    /**
     * This reservation in a new state, one version on.
     *
     * @param newState the state to move to
     * @return the updated reservation
     */
    public Reservation withState(ReservationState newState) {
        return new Reservation(id, key, lines, newState, createdAt, expiresAt, version + 1);
    }
}
