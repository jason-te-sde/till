package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.TTL;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.BatchDecision.StockWrite;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fold that turns a batch's decisions, made one after another, into the one write that makes all
 * of them true (ADR 16).
 *
 * <p>Decisions here are written out by hand rather than asked of the kernel, so that each test says
 * exactly which rows came in and which must go out; {@code TillBatchTest} and the equivalence suite
 * in {@code till-testkit} drive the same fold with what the kernel really decides.
 */
class BatchDecisionTest {

    @Test
    @DisplayName("a row several decisions wrote is written once: their last values, the version the first read, moved as often as they moved it")
    void aRowWrittenTwiceIsWrittenOnce() {
        Decision first = decision(
                new Outcome.Reserved(rid("r1"), List.of(line("widget", 2)), T0.plus(TTL)),
                List.of(new Mutation.PutStock(sku("widget"), 0, 10, 2, 5)));
        Decision second = decision(
                new Outcome.Reserved(rid("r2"), List.of(line("widget", 1)), T0.plus(TTL)),
                List.of(new Mutation.PutStock(sku("widget"), 0, 10, 3, 6)));

        BatchDecision batch = BatchDecision.of(List.of(first, second));

        assertEquals(List.of(new StockWrite(sku("widget"), 0, 10, 3, 5, 7)), batch.stock());
    }

    @Test
    @DisplayName("a row the batch creates is created once, at the version the decisions after it left it at")
    void aRowCreatedAndWrittenAgainIsCreatedAtItsLastVersion() {
        Decision created = decision(
                new Outcome.Adjusted(sku("gadget"), 5, 0),
                List.of(new Mutation.PutStock(sku("gadget"), 0, 5, 0, StockItem.ABSENT)));
        Decision held = decision(
                new Outcome.Reserved(rid("r1"), List.of(line("gadget", 1)), T0.plus(TTL)),
                List.of(new Mutation.PutStock(sku("gadget"), 0, 5, 1, 0)));

        BatchDecision batch = BatchDecision.of(List.of(created, held));

        StockWrite write = batch.stock().get(0);
        assertEquals(new StockWrite(sku("gadget"), 0, 5, 1, StockItem.ABSENT, 1), write);
        assertTrue(write.isInsert(), "the row did not exist when the batch read it");
    }

    @Test
    @DisplayName("stock rows go out in SKU and shard order, whatever order the decisions wrote them in")
    void stockRowsAreWrittenInOneOrder() {
        Decision zeta = decision(
                new Outcome.Adjusted(sku("zeta"), 4, 0), List.of(new Mutation.PutStock(sku("zeta"), 0, 4, 0, 1)));
        Decision alpha = decision(
                new Outcome.Adjusted(sku("alpha"), 9, 0),
                List.of(new Mutation.PutStock(sku("alpha"), 0, 5, 0, 2), new Mutation.PutStock(sku("alpha"), 1, 4, 0, 3)));
        Decision alphaAgain = decision(
                new Outcome.Adjusted(sku("alpha"), 8, 0), List.of(new Mutation.PutStock(sku("alpha"), 1, 3, 0, 4)));

        BatchDecision batch = BatchDecision.of(List.of(zeta, alpha, alphaAgain));

        // The same order every transaction locks rows in (ADR 9), so that two batches never wait for
        // each other in a cycle.
        assertEquals(
                List.of(
                        new StockWrite(sku("alpha"), 0, 5, 0, 2, 3),
                        new StockWrite(sku("alpha"), 1, 3, 0, 3, 5),
                        new StockWrite(sku("zeta"), 0, 4, 0, 1, 2)),
                batch.stock());
    }

    @Test
    @DisplayName("a reservation created and committed in one batch is created committed, at version 1")
    void aReservationCreatedAndCommittedIsCreatedCommitted() {
        Reservation held = new Reservation(
                rid("r1"), key("k1"), List.of(line("widget", 2)), ReservationState.HELD, T0, T0.plus(TTL), 0);
        Decision reserve = decision(
                new Outcome.Reserved(rid("r1"), List.of(line("widget", 2)), T0.plus(TTL)),
                List.of(new Mutation.PutStock(sku("widget"), 0, 10, 2, 0), new Mutation.InsertReservation(held)));
        Decision commit = decision(
                new Outcome.Committed(rid("r1"), T0),
                List.of(
                        new Mutation.PutStock(sku("widget"), 0, 8, 0, 1),
                        new Mutation.SetReservationState(rid("r1"), ReservationState.COMMITTED, 0)));

        BatchDecision batch = BatchDecision.of(List.of(reserve, commit));

        assertEquals(List.of(held.withState(ReservationState.COMMITTED)), batch.inserts());
        assertEquals(1, batch.inserts().get(0).version(), "created, then committed: one write after its first");
        assertEquals(List.of(), batch.states(), "nothing left to change on a row the batch inserts");
        assertEquals(List.of(new StockWrite(sku("widget"), 0, 8, 0, 0, 2)), batch.stock());
    }

    @Test
    @DisplayName("a reservation the batch read is moved against the version it read")
    void aReservationReadIsMovedAgainstItsVersion() {
        Decision release = decision(
                new Outcome.Released(rid("r9"), T0),
                List.of(
                        new Mutation.PutStock(sku("widget"), 0, 10, 0, 4),
                        new Mutation.SetReservationState(rid("r9"), ReservationState.RELEASED, 3)));

        BatchDecision batch = BatchDecision.of(List.of(release));

        assertEquals(List.of(new Mutation.SetReservationState(rid("r9"), ReservationState.RELEASED, 3)), batch.states());
        assertEquals(List.of(), batch.inserts());
    }

    @Test
    @DisplayName("every event and every record, in the order the decisions made them, and what each decision answered")
    void eventsRecordsAndOutcomesKeepTheirOrder() {
        Event reserved = new Event.StockReserved(rid("r1"), List.of(line("widget", 1)), T0.plus(TTL), T0);
        Event adjusted = new Event.StockAdjusted(key("d1"), sku("widget"), 5, 15, 1, T0);
        OutcomeRecord first = new OutcomeRecord(key("k1"), "f1", "o1", T0);
        OutcomeRecord second = new OutcomeRecord(key("d1"), "f2", "o2", T0);
        Outcome holding = new Outcome.Reserved(rid("r1"), List.of(line("widget", 1)), T0.plus(TTL));
        Outcome stocking = new Outcome.Adjusted(sku("widget"), 15, 1);

        BatchDecision batch = BatchDecision.of(List.of(
                new Decision(holding, List.of(), List.of(reserved), Optional.of(first)),
                new Decision(stocking, List.of(), List.of(adjusted), Optional.of(second))));

        assertEquals(List.of(reserved, adjusted), batch.events());
        assertEquals(List.of(first, second), batch.records());
        assertEquals(List.of(holding, stocking), batch.outcomes());
    }

    @Test
    @DisplayName("a decision that writes nothing, a replay, adds nothing to the batch, not even its outcome")
    void aReplayAddsNothing() {
        Decision replay = new Decision(new Outcome.Committed(rid("r1"), T0), List.of(), List.of(), Optional.empty());

        BatchDecision batch = BatchDecision.of(List.of(replay));

        // Its outcome is what a ledger reads to decide whether to wait for the disk (ADR 15), and a
        // replayed sale writes nothing that would need to.
        assertEquals(List.of(), batch.outcomes());
        assertFalse(batch.writes());
    }

    @Test
    @DisplayName("a decision made against anything but what the decisions before it left is refused, as a bug")
    void aDecisionOutOfStepIsRefused() {
        Decision first = decision(
                new Outcome.Adjusted(sku("widget"), 11, 0), List.of(new Mutation.PutStock(sku("widget"), 0, 11, 0, 5)));
        // Against version 5 again: decided against the batch's snapshot rather than against what the
        // first decision left, so it would overwrite the first decision's write with a stale one.
        Decision stale = decision(
                new Outcome.Adjusted(sku("widget"), 12, 0), List.of(new Mutation.PutStock(sku("widget"), 0, 12, 0, 5)));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> BatchDecision.of(List.of(first, stale)));
        assertTrue(thrown.getMessage().contains("widget"), thrown.getMessage());
    }

    @Test
    @DisplayName("a reservation created twice, or moved twice, in one batch is refused, as a bug")
    void aReservationTwiceIsRefused() {
        Reservation held = new Reservation(
                rid("r1"), key("k1"), List.of(line("widget", 1)), ReservationState.HELD, T0, T0.plus(TTL), 0);
        Decision created = decision(
                new Outcome.Reserved(rid("r1"), List.of(line("widget", 1)), T0.plus(TTL)),
                List.of(new Mutation.InsertReservation(held)));
        assertThrows(IllegalArgumentException.class, () -> BatchDecision.of(List.of(created, created)));

        Decision released = decision(
                new Outcome.Released(rid("r9"), T0),
                List.of(new Mutation.SetReservationState(rid("r9"), ReservationState.RELEASED, 3)));
        Decision expired = decision(
                Outcome.Rejected.of(RejectionCode.RESERVATION_EXPIRED, "r9 expired"),
                List.of(new Mutation.SetReservationState(rid("r9"), ReservationState.EXPIRED, 4)));
        assertThrows(IllegalArgumentException.class, () -> BatchDecision.of(List.of(released, expired)));
    }

    @Test
    @DisplayName("one key recorded by two decisions of a batch is refused, as a bug: the second should have been a replay")
    void aKeyRecordedTwiceIsRefused() {
        OutcomeRecord record = new OutcomeRecord(key("k1"), "f1", "o1", T0);
        Decision once = new Decision(new Outcome.Released(rid("r1"), T0), List.of(), List.of(), Optional.of(record));

        assertThrows(IllegalArgumentException.class, () -> BatchDecision.of(List.of(once, once)));
    }

    @Test
    @DisplayName("a stock write cannot move a version backwards, or leave it where it was")
    void aStockWriteMovesItsVersionForwards() {
        assertThrows(IllegalArgumentException.class, () -> new StockWrite(sku("widget"), 0, 10, 0, 5, 5));
        assertThrows(IllegalArgumentException.class, () -> new StockWrite(sku("widget"), 0, 10, 0, 5, 4));
        assertThrows(IllegalArgumentException.class, () -> new StockWrite(sku("widget"), 0, 10, 11, 5, 6));
    }

    private static Decision decision(Outcome outcome, List<Mutation> mutations) {
        return new Decision(outcome, mutations, List.of(), Optional.empty());
    }
}
