package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.TTL;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KernelReserveTest {

    @Test
    @DisplayName("a reservation raises reserved and leaves on-hand alone")
    void reservesAvailableStock() {
        Snapshot snapshot = Fixtures.snapshot().stock(sku("widget"), 10, 0, 4).build();

        Decision decision =
                Kernel.decide(
                        snapshot, new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 3)), TTL), T0);

        Outcome.Reserved reserved = assertInstanceOf(Outcome.Reserved.class, decision.outcome());
        assertEquals(rid("r1"), reserved.id());
        assertEquals(T0.plus(TTL), reserved.expiresAt());

        Mutation.PutStock put = Fixtures.only(decision, Mutation.PutStock.class);
        assertEquals(10, put.onHand(), "committing is what lowers on-hand, not reserving");
        assertEquals(3, put.reserved());
        assertEquals(4, put.expectedVersion(), "the version the decision was made against");

        Reservation written = Fixtures.only(decision, Mutation.InsertReservation.class).reservation();
        assertEquals(ReservationState.HELD, written.state());
        assertEquals(0, written.version());
        assertEquals(T0.plus(TTL), written.expiresAt());

        Event.StockReserved event = Fixtures.onlyEvent(decision, Event.StockReserved.class);
        assertEquals("reserved:r1", event.dedupeKey());
    }

    @Test
    @DisplayName("a reservation over several SKUs takes all of them or none")
    void isAllOrNothing() {
        Snapshot snapshot =
                Fixtures.snapshot()
                        .stock(sku("widget"), 10, 0, 0)
                        .stock(sku("gadget"), 1, 0, 0)
                        .stock(sku("doohickey"), 10, 0, 0)
                        .build();

        Decision decision =
                Kernel.decide(
                        snapshot,
                        new Command.Reserve(
                                key("k1"),
                                rid("r1"),
                                List.of(line("widget", 2), line("gadget", 5), line("doohickey", 1)),
                                TTL),
                        T0);

        Outcome.Rejected rejected = Fixtures.rejection(decision);
        assertEquals(RejectionCode.INSUFFICIENT_STOCK, rejected.code());
        assertTrue(
                decision.mutations().stream().noneMatch(m -> m instanceof Mutation.PutStock),
                "the two SKUs that were available must not have been touched: " + decision.mutations());
        assertTrue(decision.events().isEmpty(), "a refusal is not an event");
    }

    @Test
    @DisplayName("every short line is reported, not only the first")
    void reportsEveryShortfall() {
        Snapshot snapshot =
                Fixtures.snapshot()
                        .stock(sku("aaa"), 1, 0, 0)
                        .stock(sku("bbb"), 10, 0, 0)
                        .stock(sku("ccc"), 2, 0, 0)
                        .build();

        Decision decision =
                Kernel.decide(
                        snapshot,
                        new Command.Reserve(
                                key("k1"), rid("r1"), List.of(line("aaa", 5), line("bbb", 1), line("ccc", 9)), TTL),
                        T0);

        Outcome.Rejected rejected = Fixtures.rejection(decision);
        // A caller deciding whether to offer a smaller basket needs all of them at once. One per
        // round trip is a second chance for the numbers to move underneath it.
        assertEquals(
                List.of(
                        new Outcome.Shortfall(sku("aaa"), 5, 1),
                        new Outcome.Shortfall(sku("ccc"), 9, 2)),
                rejected.shortfalls());
        assertTrue(rejected.detail().contains("aaa"), rejected.detail());
        assertTrue(rejected.detail().contains("ccc"), rejected.detail());
    }

    @Test
    @DisplayName("reserved stock is not available to a second reservation")
    void reservedStockIsNotAvailable() {
        Snapshot snapshot = Fixtures.snapshot().stock(sku("widget"), 10, 8, 1).build();

        Decision decision =
                Kernel.decide(
                        snapshot, new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 3)), TTL), T0);

        assertEquals(RejectionCode.INSUFFICIENT_STOCK, Fixtures.rejection(decision).code());
        assertEquals(
                List.of(new Outcome.Shortfall(sku("widget"), 3, 2)),
                Fixtures.rejection(decision).shortfalls(),
                "available is on-hand minus reserved, which is 2 here and not 10");
    }

    @Test
    @DisplayName("a SKU that has never been stocked is named as unknown, not as out of stock")
    void unknownSkusAreNamed() {
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 0, 0).absent(sku("ghost")).absent(sku("spectre")).build();

        Decision decision =
                Kernel.decide(
                        snapshot,
                        new Command.Reserve(
                                key("k1"), rid("r1"), List.of(line("widget", 1), line("ghost", 1), line("spectre", 1)), TTL),
                        T0);

        Outcome.Rejected rejected = Fixtures.rejection(decision);
        assertEquals(RejectionCode.UNKNOWN_SKU, rejected.code());
        assertTrue(rejected.detail().contains("ghost"), rejected.detail());
        assertTrue(rejected.detail().contains("spectre"), rejected.detail());
    }

    @Test
    @DisplayName("a reservation id that is already taken is refused")
    void refusesADuplicateReservationId() {
        Snapshot snapshot =
                Fixtures.snapshot()
                        .stock(sku("widget"), 10, 1, 0)
                        .reservation(Fixtures.held("r1", "earlier", TTL, line("widget", 1)))
                        .build();

        Decision decision =
                Kernel.decide(
                        snapshot, new Command.Reserve(key("k2"), rid("r1"), List.of(line("widget", 1)), TTL), T0);

        assertEquals(RejectionCode.RESERVATION_ID_IN_USE, Fixtures.rejection(decision).code());
        assertTrue(decision.outcomeRecord().isPresent(), "a refusal is still an answer and is recorded");
    }

    @Test
    @DisplayName("line order does not change the request")
    void lineOrderIsNotPartOfTheRequest() {
        Command.Reserve one =
                new Command.Reserve(key("k"), rid("r"), List.of(line("bbb", 1), line("aaa", 2)), TTL);
        Command.Reserve other =
                new Command.Reserve(key("k"), rid("r"), List.of(line("aaa", 2), line("bbb", 1)), TTL);

        assertEquals(one.fingerprint(), other.fingerprint());
        assertEquals(one.lines(), other.lines(), "lines are sorted on construction");
    }

    @Test
    @DisplayName("a decision is a function of its arguments")
    void isDeterministic() {
        Snapshot snapshot = Fixtures.snapshot().stock(sku("widget"), 10, 0, 0).build();
        Command command = new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 3)), TTL);

        Decision first = Kernel.decide(snapshot, command, T0);
        Decision second = Kernel.decide(snapshot, command, T0);

        assertEquals(first, second);
        assertFalse(first.mutations() == second.mutations(), "equal by value, not the same object");
    }

    @Test
    @DisplayName("a SKU the ledger forgot to load is an adapter bug, not an out-of-stock answer")
    void missingStockRowIsAnException() {
        Snapshot snapshot = Fixtures.snapshot().build();
        Command command = new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL);

        IncompleteSnapshotException thrown =
                assertThrows(IncompleteSnapshotException.class, () -> Kernel.decide(snapshot, command, T0));
        assertTrue(thrown.getMessage().contains("widget"), thrown.getMessage());
    }
}
