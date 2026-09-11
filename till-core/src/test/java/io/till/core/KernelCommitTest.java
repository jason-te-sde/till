package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.TTL;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KernelCommitTest {

    @Test
    @DisplayName("committing lowers both on-hand and reserved")
    void commitLowersBoth() {
        Reservation held = Fixtures.held("r1", "k1", TTL, line("widget", 3));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 3, 7).reservation(held).build();

        Decision decision = Kernel.decide(snapshot, new Command.Commit(key("k2"), rid("r1")), T0);

        assertInstanceOf(Outcome.Committed.class, decision.outcome());
        Mutation.PutStock put = Fixtures.only(decision, Mutation.PutStock.class);
        assertEquals(7, put.onHand(), "the goods left");
        assertEquals(0, put.reserved(), "and the hold on them left with them");
        assertEquals(7, put.expectedVersion());

        Mutation.SetReservationState state = Fixtures.only(decision, Mutation.SetReservationState.class);
        assertEquals(ReservationState.COMMITTED, state.state());
        assertEquals(0, state.expectedVersion());
        assertEquals("committed:r1", Fixtures.onlyEvent(decision, Event.StockCommitted.class).dedupeKey());
    }

    @Test
    @DisplayName("committing every line of a multi-SKU hold in one decision")
    void commitsEveryLine() {
        Reservation held = Fixtures.held("r1", "k1", TTL, line("widget", 2), line("gadget", 1));
        Snapshot snapshot =
                Fixtures.snapshot()
                        .stock(sku("widget"), 10, 2, 0)
                        .stock(sku("gadget"), 5, 1, 0)
                        .reservation(held)
                        .build();

        Decision decision = Kernel.decide(snapshot, new Command.Commit(key("k2"), rid("r1")), T0);

        List<Mutation.PutStock> puts =
                decision.mutations().stream()
                        .filter(Mutation.PutStock.class::isInstance)
                        .map(Mutation.PutStock.class::cast)
                        .toList();
        assertEquals(2, puts.size());
        assertEquals(sku("gadget"), puts.get(0).sku(), "stock rows come out in SKU order, so a test can name them");
        assertEquals(4, puts.get(0).onHand());
        assertEquals(8, puts.get(1).onHand());
    }

    @Test
    @DisplayName("a reservation that does not exist is not found")
    void missingReservation() {
        Decision decision =
                Kernel.decide(Fixtures.snapshot().build(), new Command.Commit(key("k"), rid("nope")), T0);

        assertEquals(RejectionCode.RESERVATION_NOT_FOUND, Fixtures.rejection(decision).code());
    }

    @Test
    @DisplayName("committing twice is refused, because stock cannot leave twice")
    void refusesASecondCommit() {
        Reservation committed =
                Fixtures.held("r1", "k1", TTL, line("widget", 3)).withState(ReservationState.COMMITTED);
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 7, 0, 8).reservation(committed).build();

        Decision decision = Kernel.decide(snapshot, new Command.Commit(key("k3"), rid("r1")), T0);

        assertEquals(RejectionCode.ALREADY_COMMITTED, Fixtures.rejection(decision).code());
        assertTrue(decision.mutations().isEmpty());
    }

    @Test
    @DisplayName("committing a released hold is refused")
    void refusesCommitAfterRelease() {
        Reservation released =
                Fixtures.held("r1", "k1", TTL, line("widget", 3)).withState(ReservationState.RELEASED);
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 0, 9).reservation(released).build();

        Decision decision = Kernel.decide(snapshot, new Command.Commit(key("k3"), rid("r1")), T0);

        assertEquals(RejectionCode.ALREADY_RELEASED, Fixtures.rejection(decision).code());
    }

    @Test
    @DisplayName("committing after the deadline is refused and writes the hold off in the same decision")
    void refusesCommitAfterTheDeadlineAndReclaims() {
        Reservation held = Fixtures.held("r1", "k1", Duration.ofMinutes(15), line("widget", 3));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 3, 2).reservation(held).build();

        // One second past the deadline, and nothing has swept: the row still says HELD.
        Decision decision =
                Kernel.decide(snapshot, new Command.Commit(key("k2"), rid("r1")), T0.plusSeconds(901));

        assertEquals(RejectionCode.RESERVATION_EXPIRED, Fixtures.rejection(decision).code());
        assertEquals(
                ReservationState.EXPIRED,
                Fixtures.only(decision, Mutation.SetReservationState.class).state(),
                "the expiry was just established; discarding it would mean establishing it again");
        assertEquals(0, Fixtures.only(decision, Mutation.PutStock.class).reserved(), "and the stock came back");
        assertEquals("expired:r1", Fixtures.onlyEvent(decision, Event.StockExpired.class).dedupeKey());
    }

    @Test
    @DisplayName("committing exactly at the deadline is too late")
    void theDeadlineIsExclusive() {
        Reservation held = Fixtures.held("r1", "k1", Duration.ofMinutes(15), line("widget", 3));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 3, 0).reservation(held).build();

        Decision decision = Kernel.decide(snapshot, new Command.Commit(key("k2"), rid("r1")), held.expiresAt());

        assertEquals(
                RejectionCode.RESERVATION_EXPIRED,
                Fixtures.rejection(decision).code(),
                "expiresAt is when the hold stops counting, not the last instant it counts");
    }

    @Test
    @DisplayName("committing a hold that was already written off does not return its stock twice")
    void doesNotReclaimAnAlreadyExpiredHold() {
        // Found by the simulator at seed 1, step 66, on its first run. effectiveState says EXPIRED
        // both for a hold still stored as HELD whose deadline has passed and for one written off an
        // hour ago, and the commit path acted on that answer rather than on the stored state. Only
        // the first still has stock to give back; the second took reserved to -4.
        Reservation expired =
                Fixtures.held("r1", "k1", Duration.ofMinutes(15), line("widget", 4))
                        .withState(ReservationState.EXPIRED);
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 40, 0, 3).reservation(expired).build();

        Decision decision =
                Kernel.decide(snapshot, new Command.Commit(key("k2"), rid("r1")), T0.plusSeconds(3600));

        assertEquals(RejectionCode.RESERVATION_EXPIRED, Fixtures.rejection(decision).code());
        assertTrue(decision.mutations().isEmpty(), "the stock was returned when it expired, not now");
        assertTrue(decision.events().isEmpty(), "and the expiry was announced then, not now");
    }

    @Test
    @DisplayName("a SKU the ledger forgot names the reservation that needed it")
    void incompleteSnapshotNamesTheReservation() {
        Reservation held = Fixtures.held("r1", "k1", TTL, line("widget", 3));
        Snapshot snapshot = Fixtures.snapshot().reservation(held).build();

        IncompleteSnapshotException thrown =
                assertThrows(
                        IncompleteSnapshotException.class,
                        () -> Kernel.decide(snapshot, new Command.Commit(key("k2"), rid("r1")), T0));
        assertTrue(thrown.getMessage().contains("widget"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("r1"), thrown.getMessage());
    }
}
