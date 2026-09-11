package io.till.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Shorthand for the values every kernel test needs. */
final class Fixtures {

    /** A fixed instant, so that a failure message reads the same on every machine. */
    static final Instant T0 = Instant.parse("2026-09-10T12:00:00Z");

    static final Duration TTL = Duration.ofMinutes(15);

    private Fixtures() {}

    static Sku sku(String value) {
        return Sku.of(value);
    }

    static IdempotencyKey key(String value) {
        return IdempotencyKey.of(value);
    }

    static ReservationId rid(String value) {
        return ReservationId.of(value);
    }

    static Line line(String sku, long quantity) {
        return Line.of(sku, quantity);
    }

    /** A held reservation created at {@link #T0} that runs out after {@code ttl}. */
    static Reservation held(String id, String key, Duration ttl, Line... lines) {
        return new Reservation(
                rid(id), key(key), List.of(lines), ReservationState.HELD, T0, T0.plus(ttl), 0);
    }

    static Snapshot.Builder snapshot() {
        return Snapshot.builder();
    }

    /** The first mutation of the given kind, which every caller here expects to exist. */
    static <T extends Mutation> T only(Decision decision, Class<T> type) {
        return decision.mutations().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "no " + type.getSimpleName() + " in " + decision.mutations()));
    }

    static <T extends Event> T onlyEvent(Decision decision, Class<T> type) {
        return decision.events().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in " + decision.events()));
    }

    static Outcome.Rejected rejection(Decision decision) {
        if (decision.outcome() instanceof Outcome.Rejected rejected) {
            return rejected;
        }
        throw new AssertionError("expected a rejection, got " + decision.outcome());
    }
}
