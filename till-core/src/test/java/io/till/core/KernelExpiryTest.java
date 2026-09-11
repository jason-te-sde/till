package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.TTL;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule that a deadline, not a stored state, decides whether a hold counts.
 *
 * <p>This is the property that makes the background sweep an optimisation rather than a correctness
 * requirement, and it is the one a naive implementation gets wrong: reading {@code state = 'HELD'}
 * straight out of the row makes every answer depend on whether a job happened to have run.
 */
class KernelExpiryTest {

    @Test
    @DisplayName("an expired hold does not stand in the way of a new reservation")
    void expiredHoldsAreReclaimedForANewReservation() {
        Reservation stale = Fixtures.held("r0", "k0", Duration.ofMinutes(1), line("widget", 10));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 10, 3).reclaimable(stale).build();

        // Every unit is reserved by a hold that ran out fifty-nine minutes ago.
        Decision decision =
                Kernel.decide(
                        snapshot,
                        new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 4)), TTL),
                        T0.plus(Duration.ofHours(1)));

        assertInstanceOf(Outcome.Reserved.class, decision.outcome());
        Mutation.PutStock put = Fixtures.only(decision, Mutation.PutStock.class);
        assertEquals(4, put.reserved(), "ten came back and four went out again");
        assertEquals(3, put.expectedVersion(), "one row change, at the version the snapshot had");
        assertEquals(
                1,
                decision.mutations().stream().filter(Mutation.PutStock.class::isInstance).count(),
                "a SKU touched twice in one decision produces one row change, not two that conflict");
        assertEquals(
                ReservationState.EXPIRED, Fixtures.only(decision, Mutation.SetReservationState.class).state());
    }

    @Test
    @DisplayName("a hold that has not run out yet is left alone")
    void liveHoldsAreNotReclaimed() {
        Reservation live = Fixtures.held("r0", "k0", Duration.ofHours(2), line("widget", 10));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 10, 0).reclaimable(live).build();

        Decision decision =
                Kernel.decide(
                        snapshot,
                        new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL),
                        T0.plusSeconds(60));

        assertEquals(RejectionCode.INSUFFICIENT_STOCK, Fixtures.rejection(decision).code());
        assertTrue(decision.mutations().stream().noneMatch(Mutation.SetReservationState.class::isInstance));
    }

    @Test
    @DisplayName("a sweep writes off at most as many as it was asked to")
    void sweepHonoursItsLimit() {
        List<Reservation> stale =
                List.of(
                        Fixtures.held("r1", "k1", Duration.ofMinutes(1), line("widget", 1)),
                        Fixtures.held("r2", "k2", Duration.ofMinutes(1), line("widget", 1)),
                        Fixtures.held("r3", "k3", Duration.ofMinutes(1), line("widget", 1)));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 3, 0).reclaimable(stale).build();

        Decision decision = Kernel.decide(snapshot, new Command.Sweep(2), T0.plusSeconds(3600));

        assertEquals(2, assertInstanceOf(Outcome.Swept.class, decision.outcome()).reclaimed());
        assertEquals(1, Fixtures.only(decision, Mutation.PutStock.class).reserved(), "two of three came back");
        assertTrue(decision.outcomeRecord().isEmpty(), "a sweep has no key and records nothing");
    }

    @Test
    @DisplayName("a sweep that finds nothing writes nothing at all")
    void emptySweepWritesNothing() {
        Decision decision = Kernel.decide(Fixtures.snapshot().build(), new Command.Sweep(10), T0);

        assertEquals(0, assertInstanceOf(Outcome.Swept.class, decision.outcome()).reclaimed());
        assertTrue(decision.writes() == false, "so Till never opens a transaction for it");
    }

    @Test
    @DisplayName("holds are written off in id order, whatever order the ledger offered them in")
    void reclaimOrderIsDeterministic() {
        List<Reservation> offered =
                List.of(
                        Fixtures.held("r9", "k9", Duration.ofMinutes(1), line("widget", 1)),
                        Fixtures.held("r1", "k1", Duration.ofMinutes(1), line("widget", 1)));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 2, 0).reclaimable(offered).build();

        Decision decision = Kernel.decide(snapshot, new Command.Sweep(10), T0.plusSeconds(3600));

        List<ReservationId> order =
                decision.mutations().stream()
                        .filter(Mutation.SetReservationState.class::isInstance)
                        .map(m -> ((Mutation.SetReservationState) m).reservationId())
                        .toList();
        assertEquals(List.of(rid("r1"), rid("r9")), order);
    }

    @Test
    @DisplayName("the same hold offered twice is written off once")
    void duplicatesAreIgnored() {
        Reservation stale = Fixtures.held("r1", "k1", Duration.ofMinutes(1), line("widget", 4));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 4, 0).reclaimable(List.of(stale, stale)).build();

        Decision decision = Kernel.decide(snapshot, new Command.Sweep(10), T0.plusSeconds(3600));

        assertEquals(1, assertInstanceOf(Outcome.Swept.class, decision.outcome()).reclaimed());
        assertEquals(
                0,
                Fixtures.only(decision, Mutation.PutStock.class).reserved(),
                "writing it off twice would take reserved negative, which StockItem refuses");
    }

    @Test
    @DisplayName("reclaiming never touches the reservation the command is about")
    void theCommandOwnsItsOwnReservation() {
        Reservation target = Fixtures.held("r1", "k1", Duration.ofMinutes(1), line("widget", 4));
        Snapshot snapshot =
                Fixtures.snapshot()
                        .stock(sku("widget"), 10, 4, 0)
                        .reservation(target)
                        .reclaimable(target)
                        .build();

        Decision decision =
                Kernel.decide(snapshot, new Command.Commit(key("k2"), rid("r1")), T0.plusSeconds(3600));

        // Expired once, by the commit path, which is the one that knows this has to be a refusal.
        assertEquals(RejectionCode.RESERVATION_EXPIRED, Fixtures.rejection(decision).code());
        assertEquals(
                1,
                decision.mutations().stream().filter(Mutation.SetReservationState.class::isInstance).count());
        assertEquals(0, Fixtures.only(decision, Mutation.PutStock.class).reserved());
    }
}
