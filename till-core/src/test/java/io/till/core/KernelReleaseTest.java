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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KernelReleaseTest {

    @Test
    @DisplayName("releasing lowers reserved and leaves on-hand alone")
    void releaseReturnsStock() {
        Reservation held = Fixtures.held("r1", "k1", TTL, line("widget", 3));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 3, 5).reservation(held).build();

        Decision decision = Kernel.decide(snapshot, new Command.Release(key("k2"), rid("r1")), T0);

        assertInstanceOf(Outcome.Released.class, decision.outcome());
        Mutation.PutStock put = Fixtures.only(decision, Mutation.PutStock.class);
        assertEquals(10, put.onHand(), "nothing left the building");
        assertEquals(0, put.reserved());
        assertEquals(
                ReservationState.RELEASED, Fixtures.only(decision, Mutation.SetReservationState.class).state());
        assertEquals("released:r1", Fixtures.onlyEvent(decision, Event.StockReleased.class).dedupeKey());
    }

    @Test
    @DisplayName("releasing an already released hold succeeds and changes nothing")
    void releaseIsIdempotentByNature() {
        Reservation released =
                Fixtures.held("r1", "k1", TTL, line("widget", 3)).withState(ReservationState.RELEASED);
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 0, 6).reservation(released).build();

        // A different key, so this is a genuine second call rather than a replay. A cancel path that
        // has to distinguish "cancelled" from "already cancelled" is a cancel path with a bug in it.
        Decision decision = Kernel.decide(snapshot, new Command.Release(key("k9"), rid("r1")), T0);

        assertInstanceOf(Outcome.Released.class, decision.outcome());
        assertTrue(decision.mutations().isEmpty(), "it was already in the state that was asked for");
        assertTrue(decision.events().isEmpty());
    }

    @Test
    @DisplayName("releasing a committed hold is refused, because the goods already left")
    void refusesReleaseAfterCommit() {
        Reservation committed =
                Fixtures.held("r1", "k1", TTL, line("widget", 3)).withState(ReservationState.COMMITTED);
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 7, 0, 6).reservation(committed).build();

        Decision decision = Kernel.decide(snapshot, new Command.Release(key("k9"), rid("r1")), T0);

        assertEquals(RejectionCode.ALREADY_COMMITTED, Fixtures.rejection(decision).code());
    }

    @Test
    @DisplayName("releasing an expired hold succeeds and writes the expiry off")
    void releaseAfterDeadlineSucceeds() {
        Reservation held = Fixtures.held("r1", "k1", Duration.ofMinutes(15), line("widget", 3));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 3, 0).reservation(held).build();

        Decision decision =
                Kernel.decide(snapshot, new Command.Release(key("k2"), rid("r1")), T0.plusSeconds(901));

        assertInstanceOf(Outcome.Released.class, decision.outcome());
        assertEquals(
                ReservationState.EXPIRED,
                Fixtures.only(decision, Mutation.SetReservationState.class).state(),
                "it expired rather than being released, and the event says so");
        assertEquals(0, Fixtures.only(decision, Mutation.PutStock.class).reserved());
        assertEquals("expired:r1", Fixtures.onlyEvent(decision, Event.StockExpired.class).dedupeKey());
    }

    @Test
    @DisplayName("releasing a hold that was already written off does not return its stock twice")
    void doesNotReclaimAnAlreadyExpiredHold() {
        Reservation expired =
                Fixtures.held("r1", "k1", Duration.ofMinutes(15), line("widget", 4))
                        .withState(ReservationState.EXPIRED);
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 40, 0, 3).reservation(expired).build();

        Decision decision =
                Kernel.decide(snapshot, new Command.Release(key("k2"), rid("r1")), T0.plusSeconds(3600));

        assertInstanceOf(Outcome.Released.class, decision.outcome());
        assertTrue(decision.mutations().isEmpty(), "it is already in the state that was asked for");
    }

    @Test
    @DisplayName("releasing a reservation that does not exist is not found")
    void missingReservation() {
        Decision decision =
                Kernel.decide(Fixtures.snapshot().build(), new Command.Release(key("k"), rid("nope")), T0);

        assertEquals(RejectionCode.RESERVATION_NOT_FOUND, Fixtures.rejection(decision).code());
    }
}
