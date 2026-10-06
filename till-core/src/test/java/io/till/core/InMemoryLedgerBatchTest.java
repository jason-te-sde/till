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

import io.till.core.mem.InMemoryLedger;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two halves of the ledger contract a batch uses (ADR 16), on the ledger in a few maps: reading
 * what many commands need at one instant, and writing what they decided all at once.
 *
 * <p>{@code JdbcLedgerBatchTest} holds PostgreSQL to the same statements, and the equivalence suite
 * in {@code till-testkit} holds both to answering exactly as one command at a time does.
 */
class InMemoryLedgerBatchTest {

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final Till till = Till.builder(ledger).clock(Clock.fixed(T0, ZoneOffset.UTC)).build();

    @Test
    @DisplayName("a batch's snapshot, seeded into a ledger of its own, answers every command's load as the ledger it came from does")
    void aBatchSnapshotAnswersEveryLoad() {
        populate();
        List<Command> commands = everyShapeOfCommand();

        InMemoryLedger seeded = InMemoryLedger.from(ledger.loadBatch(commands));

        for (Command command : commands) {
            Snapshot expected = ledger.load(command, T0, 0);
            Snapshot actual = seeded.load(command, T0, 0);
            assertEquals(expected, actual, "the seeded ledger loads something else for " + command);
            assertEquals(
                    List.copyOf(expected.stock().keySet()),
                    List.copyOf(actual.stock().keySet()),
                    "the SKUs come in a different order for " + command);
        }
    }

    @Test
    @DisplayName("a batch's snapshot holds each SKU once with every shard, a SKU with no row as none, and only the rows its commands name")
    void aBatchSnapshotHoldsWhatItsCommandsName() {
        populate();

        BatchSnapshot snapshot = ledger.loadBatch(List.of(
                new Command.Reserve(key("fresh"), rid("r-new"), List.of(line("widget", 1), line("ghost", 1)), TTL),
                new Command.Commit(key("pay-1"), rid("r1")),
                new Command.Reserve(key("hold-1"), rid("r-again"), List.of(line("widget", 2)), TTL)));

        // Hand-derived from populate(): widget in one row, gadget in four, the ghost in none; r1 holds
        // widget and gadget, so the commit brings gadget in although it names no SKU of its own. In the
        // order the commands name them, a reserve's lines sorted by SKU: ghost, widget, then gadget.
        assertEquals(List.of(sku("ghost"), sku("widget"), sku("gadget")), List.copyOf(snapshot.stock().keySet()));
        assertEquals(List.of(0, 1, 4), snapshot.stock().values().stream().map(List::size).toList());
        assertEquals(List.of(rid("r1")), List.copyOf(snapshot.reservations().keySet()));
        assertEquals(List.of(key("hold-1")), List.copyOf(snapshot.records().keySet()), "pay-1 and fresh were never used");
        assertFalse(snapshot.reservations().containsKey(rid("r2")), "r2 exists, and nothing in the batch names it");
    }

    @Test
    @DisplayName("a batch's snapshot refuses a reservation whose SKUs it does not hold, rather than letting them look unstocked")
    void aBatchSnapshotRefusesAReservationWithoutItsStock() {
        Reservation held = Fixtures.held("r1", "k1", TTL, line("widget", 1));

        assertThrows(
                IncompleteSnapshotException.class,
                () -> new BatchSnapshot(Map.of(), Map.of(rid("r1"), held), Map.of()));
    }

    @Test
    @DisplayName("a command naming a SKU the batch's snapshot does not hold is refused before it is decided")
    void aCommandOutsideTheSnapshotIsRefused() {
        BatchSnapshot snapshot = new BatchSnapshot(
                Map.of(sku("widget"), List.of(new StockShard(sku("widget"), 0, 10, 0, 3))), Map.of(), Map.of());

        snapshot.requireCovers(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL));
        IncompleteSnapshotException thrown = assertThrows(
                IncompleteSnapshotException.class,
                () -> snapshot.requireCovers(
                        new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 1), line("gadget", 1)), TTL)));
        assertTrue(thrown.getMessage().contains("gadget"), thrown.getMessage());
    }

    @Test
    @DisplayName("a batch's net change, written, leaves the ledger as the commands left the ledger they were decided on")
    void aBatchIsWrittenAsItWasDecided() {
        populate();
        // Gadget has one unit to spare: four on hand, three of them held by r1.
        List<Command> commands = List.of(
                new Command.Reserve(key("b1"), rid("b-r1"), List.of(line("widget", 3), line("gadget", 1)), TTL),
                new Command.Commit(key("b2"), rid("b-r1")),
                new Command.Release(key("b3"), rid("r1")),
                new Command.Adjust(key("b4"), sku("ghost"), 7),
                new Command.Reserve(key("b5"), rid("b-r5"), List.of(line("ghost", 2)), TTL),
                new Command.Shard(key("b6"), sku("widget"), 3));
        InMemoryLedger decidedOn = InMemoryLedger.from(ledger.loadBatch(commands));
        List<Decision> decisions = new ArrayList<>();
        for (Command command : commands) {
            Decision decision = Kernel.decide(decidedOn.load(command, T0, 0), command, T0);
            assertTrue(decidedOn.apply(decision), "deciding on a ledger of its own, nothing can have moved");
            decisions.add(decision);
        }
        assertEquals(
                List.of(Outcome.Reserved.class, Outcome.Committed.class, Outcome.Released.class, Outcome.Adjusted.class,
                        Outcome.Reserved.class, Outcome.Sharded.class),
                decisions.stream().map(decision -> decision.outcome().getClass()).toList(),
                "the batch was meant to do one of each, every one of them writing: " + decisions);
        Map<Sku, List<StockShard>> before = shardsOf(ledger);

        assertTrue(ledger.applyBatch(BatchDecision.of(decisions)));

        // Every row the batch read is where the batch left it, its version included; every other row
        // is where it was.
        Map<Sku, List<StockShard>> expected = new LinkedHashMap<>(before);
        expected.putAll(shardsOf(decidedOn));
        assertEquals(expected, shardsOf(ledger));
        for (Reservation reservation : decidedOn.allReservations()) {
            assertEquals(Optional.of(reservation), ledger.reservation(reservation.id()));
        }
        assertEquals(
                List.of("reserved:b-r1", "committed:b-r1", "released:r1", "adjusted:b4", "reserved:b-r5"),
                ledger.allEvents().stream().skip(eventsBefore).map(OutboxEntry::dedupeKey).toList());
        for (Command command : commands) {
            Optional<OutcomeRecord> record = ledger.load(command, T0, 0).recordedOutcome();
            assertEquals(decidedOn.load(command, T0, 0).recordedOutcome(), record, "the record of " + command);
            assertTrue(record.isPresent(), "every command of the batch is answered for good: " + command);
        }
    }

    @Test
    @DisplayName("a batch with one row that moved since it was read writes nothing at all")
    void aStaleBatchWritesNothing() {
        populate();
        List<Command> commands = List.of(
                new Command.Reserve(key("b1"), rid("b-r1"), List.of(line("gadget", 2)), TTL),
                new Command.Reserve(key("b2"), rid("b-r2"), List.of(line("widget", 1)), TTL));
        BatchDecision batch = decide(commands);
        // Somebody else wrote widget's row between the batch's read and its write.
        till.adjust(key("elsewhere"), sku("widget"), 1);
        Map<Sku, List<StockShard>> before = shardsOf(ledger);
        int events = ledger.allEvents().size();
        int records = ledger.recordCount();

        assertFalse(ledger.applyBatch(batch));

        assertEquals(before, shardsOf(ledger), "gadget's row, which had not moved, is not written either");
        assertTrue(ledger.reservation(rid("b-r1")).isEmpty());
        assertTrue(ledger.reservation(rid("b-r2")).isEmpty());
        assertEquals(events, ledger.allEvents().size());
        assertEquals(records, ledger.recordCount());
        assertEquals(1, ledger.conflictCount());
    }

    @Test
    @DisplayName("a batch whose key, or new reservation's id, somebody else took first writes nothing at all")
    void aBatchWhoseKeyWasTakenWritesNothing() {
        populate();
        BatchDecision claimsAKey = decide(List.of(
                new Command.Reserve(key("b1"), rid("b-r1"), List.of(line("gadget", 1)), TTL),
                new Command.Release(key("racing"), rid("r2"))));
        BatchDecision claimsAnId = decide(List.of(
                new Command.Reserve(key("b3"), rid("b-r3"), List.of(line("gadget", 1)), TTL)));
        // The same key, and the same id, written by a decision of somebody else's in the meantime;
        // neither touches a row the batches wrote.
        till.adjust(key("racing"), sku("ghost"), 1);
        till.reserve(key("other"), rid("b-r3"), List.of(line("ghost", 1)), TTL);

        assertFalse(ledger.applyBatch(claimsAKey));
        assertFalse(ledger.applyBatch(claimsAnId));

        assertTrue(ledger.reservation(rid("b-r1")).isEmpty(), "nothing of the first batch was written");
        assertEquals(List.of(line("ghost", 1)), ledger.reservation(rid("b-r3")).orElseThrow().lines(), "nor of the second");
    }

    /** Decides commands against a ledger seeded from this one's batch snapshot, as Till does, and folds them. */
    private BatchDecision decide(List<Command> commands) {
        InMemoryLedger decidedOn = InMemoryLedger.from(ledger.loadBatch(commands));
        List<Decision> decisions = new ArrayList<>();
        for (Command command : commands) {
            Decision decision = Kernel.decide(decidedOn.load(command, T0, 0), command, T0);
            assertTrue(decidedOn.apply(decision));
            decisions.add(decision);
        }
        return BatchDecision.of(decisions);
    }

    private int eventsBefore;

    /**
     * A ledger with the shapes a batch has to read: a SKU in one row and one in four; a hold across
     * both, with the four-row SKU's units from more than one row; a second hold; a key already used.
     */
    private void populate() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 2);
        till.shard(key("s2"), sku("gadget"), 4);
        till.adjust(key("d3"), sku("gadget"), 2);
        till.reserve(key("hold-1"), rid("r1"), List.of(line("widget", 1), line("gadget", 3)), TTL);
        till.reserve(key("hold-2"), rid("r2"), List.of(line("widget", 2)), TTL);
        assertTrue(
                ledger.reservation(rid("r1")).orElseThrow().allocations().size() > 2,
                "gadget's three units were meant to come from more than one row");
        eventsBefore = ledger.allEvents().size();
    }

    /** One of every shape a command takes against {@link #populate()}'s ledger. */
    private static List<Command> everyShapeOfCommand() {
        return List.of(
                new Command.Reserve(key("fresh"), rid("r-new"), List.of(line("widget", 1), line("ghost", 1)), TTL),
                new Command.Reserve(key("hold-1"), rid("r-retry"), List.of(line("widget", 1), line("gadget", 3)), TTL),
                new Command.Reserve(key("taken-id"), rid("r1"), List.of(line("gadget", 1)), TTL),
                new Command.Commit(key("pay-1"), rid("r1")),
                new Command.Release(key("cancel-2"), rid("r2")),
                new Command.Release(key("cancel-x"), rid("missing")),
                new Command.Adjust(key("d9"), sku("gadget"), -1),
                new Command.Shard(key("s9"), sku("widget"), 2));
    }

    private static Map<Sku, List<StockShard>> shardsOf(InMemoryLedger ledger) {
        Map<Sku, List<StockShard>> shards = new LinkedHashMap<>();
        ledger.allShards().forEach(shard -> shards.computeIfAbsent(shard.sku(), ignored -> new ArrayList<>()).add(shard));
        return shards;
    }
}
