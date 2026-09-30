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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A SKU kept in several rows (ADR 9): where holds take their units, and where they give them back. */
class KernelShardTest {

    private static final Sku WIDGET = sku("widget");

    @Test
    @DisplayName("a split deals the unreserved units out evenly, and leaves every hold's units where they are")
    void splitting() {
        Snapshot snapshot = Fixtures.snapshot().stock(WIDGET, 10, 3, 5).build();

        Decision decision = Kernel.decide(snapshot, new Command.Shard(key("split"), WIDGET, 4), T0);

        assertEquals(new Outcome.Sharded(WIDGET, 4), decision.outcome());
        assertEquals(
                List.of(
                        new Mutation.PutStock(WIDGET, 0, 5, 3, 5),
                        new Mutation.PutStock(WIDGET, 1, 2, 0, StockItem.ABSENT),
                        new Mutation.PutStock(WIDGET, 2, 2, 0, StockItem.ABSENT),
                        new Mutation.PutStock(WIDGET, 3, 1, 0, StockItem.ABSENT)),
                decision.mutations(),
                "seven available over four shards, and shard 0 keeps the three its holds reserved");
        assertTrue(decision.events().isEmpty(), "the SKU's on-hand and reserved are what they were");
    }

    @Test
    @DisplayName("a split with nothing available still creates the rows, at zero")
    void splittingNothing() {
        Snapshot snapshot = Fixtures.snapshot().stock(WIDGET, 4, 4, 1).build();

        Decision decision = Kernel.decide(snapshot, new Command.Shard(key("split"), WIDGET, 2), T0);

        assertEquals(List.of(new Mutation.PutStock(WIDGET, 1, 0, 0, StockItem.ABSENT)), decision.mutations());
    }

    @Test
    @DisplayName("a SKU already in that many shards is left as it is")
    void splittingAgain() {
        Snapshot snapshot = sharded(5, 5, 5, 5);

        Decision decision = Kernel.decide(snapshot, new Command.Shard(key("again"), WIDGET, 2), T0);

        assertEquals(new Outcome.Sharded(WIDGET, 4), decision.outcome(), "what it has, which is more");
        assertTrue(decision.mutations().isEmpty());
    }

    @Test
    @DisplayName("a SKU with no row cannot be split")
    void splittingNothingAtAll() {
        Decision decision =
                Kernel.decide(Fixtures.snapshot().absent(WIDGET).build(), new Command.Shard(key("s"), WIDGET, 4), T0);

        assertEquals(RejectionCode.UNKNOWN_SKU, Fixtures.rejection(decision).code());
    }

    @Test
    @DisplayName("a hold takes its units from one shard, and different holds spread over all of them")
    void holdsSpread() {
        Snapshot snapshot = sharded(50, 50, 50, 50);
        Map<Integer, Integer> chosen = new HashMap<>();

        for (int i = 0; i < 400; i++) {
            Decision decision = reserve(snapshot, "r-" + i, 3);
            Reservation held = Fixtures.only(decision, Mutation.InsertReservation.class).reservation();
            assertEquals(1, held.allocations().size(), "one shard has all of it: " + held.allocations());
            Allocation allocation = held.allocations().get(0);
            assertEquals(3, allocation.quantity());
            Mutation.PutStock put = Fixtures.only(decision, Mutation.PutStock.class);
            assertEquals(allocation.shard(), put.shard(), "the row written is the one the units came from");
            assertEquals(3, put.reserved());
            chosen.merge(allocation.shard(), 1, Integer::sum);
        }

        assertEquals(4, chosen.size(), "every shard: " + chosen);
        chosen.values().forEach(count -> assertTrue(count > 60, "roughly a quarter each: " + chosen));
    }

    @Test
    @DisplayName("the same reservation id takes from the same shard, so a replayed decision writes the same rows")
    void sameIdSameShard() {
        Snapshot snapshot = sharded(50, 50, 50, 50);

        assertEquals(reserve(snapshot, "r-7", 1).mutations(), reserve(snapshot, "r-7", 1).mutations());
    }

    @Test
    @DisplayName("a hold its shard cannot cover moves on, in order, to the next that can")
    void movesOn() {
        String id = idStartingAt(2);
        Snapshot snapshot = sharded(9, 9, 1, 0);

        Decision decision = reserve(snapshot, id, 5);

        Reservation held = Fixtures.only(decision, Mutation.InsertReservation.class).reservation();
        assertEquals(List.of(new Allocation(WIDGET, 0, 5)), held.allocations(), "shard 2 has 1, shard 3 none, then 0");
    }

    @Test
    @DisplayName("a hold no one shard can cover takes its units from several, and still holds one line")
    void splitsAHold() {
        String id = idStartingAt(1);
        Snapshot snapshot = sharded(2, 2, 2, 2);

        Decision decision = reserve(snapshot, id, 7);

        Reservation held = Fixtures.only(decision, Mutation.InsertReservation.class).reservation();
        assertEquals(List.of(line("widget", 7)), held.lines());
        assertEquals(
                List.of(new Allocation(WIDGET, 0, 1), new Allocation(WIDGET, 1, 2), new Allocation(WIDGET, 2, 2),
                        new Allocation(WIDGET, 3, 2)),
                held.allocations(),
                "from shard 1 on, as much as each has, until it is covered");
        assertEquals(4, decision.mutations().stream().filter(Mutation.PutStock.class::isInstance).count());
        Event.StockReserved event = Fixtures.onlyEvent(decision, Event.StockReserved.class);
        assertEquals(List.of(line("widget", 7)), event.lines(), "the event says what, never where");
    }

    @Test
    @DisplayName("a hold is refused only when the SKU as a whole is short")
    void refusedForTheWholeSku() {
        Snapshot snapshot = sharded(2, 2, 2, 2);

        Outcome.Rejected refused = Fixtures.rejection(reserve(snapshot, "r-1", 9));

        assertEquals(RejectionCode.INSUFFICIENT_STOCK, refused.code());
        assertEquals(List.of(new Outcome.Shortfall(WIDGET, 9, 8)), refused.shortfalls());
    }

    @Test
    @DisplayName("committing gives each allocation's units to the shard it came from")
    void commitReturnsToTheShards() {
        Reservation held = holding("r1", new Allocation(WIDGET, 1, 2), new Allocation(WIDGET, 3, 1));
        Snapshot snapshot = Fixtures.snapshot()
                .shard(new StockShard(WIDGET, 0, 5, 0, 0))
                .shard(new StockShard(WIDGET, 1, 5, 2, 4))
                .shard(new StockShard(WIDGET, 2, 5, 0, 0))
                .shard(new StockShard(WIDGET, 3, 5, 1, 9))
                .reservation(held)
                .build();

        Decision decision = Kernel.decide(snapshot, new Command.Commit(key("pay"), rid("r1")), T0);

        assertInstanceOf(Outcome.Committed.class, decision.outcome());
        assertEquals(
                List.of(new Mutation.PutStock(WIDGET, 1, 3, 0, 4), new Mutation.PutStock(WIDGET, 3, 4, 0, 9)),
                decision.mutations().stream().filter(Mutation.PutStock.class::isInstance).toList());
        assertEquals(List.of(line("widget", 3)), Fixtures.onlyEvent(decision, Event.StockCommitted.class).lines());
    }

    @Test
    @DisplayName("releasing, and expiring, give the units back to the shards they came from")
    void releaseAndExpiryReturnToTheShards() {
        Reservation held = holding("r1", new Allocation(WIDGET, 0, 1), new Allocation(WIDGET, 2, 4));
        Snapshot.Builder builder = Fixtures.snapshot()
                .shard(new StockShard(WIDGET, 0, 5, 1, 1))
                .shard(new StockShard(WIDGET, 1, 5, 0, 0))
                .shard(new StockShard(WIDGET, 2, 5, 4, 2));

        Decision released = Kernel.decide(
                builder.reservation(held).build(), new Command.Release(key("cancel"), rid("r1")), T0);
        assertEquals(
                List.of(new Mutation.PutStock(WIDGET, 0, 5, 0, 1), new Mutation.PutStock(WIDGET, 2, 5, 0, 2)),
                released.mutations().stream().filter(Mutation.PutStock.class::isInstance).toList());

        Decision expired = Kernel.decide(
                builder.reservation(null).reclaimable(held).build(), new Command.Sweep(10), T0.plus(TTL));
        assertEquals(
                List.of(new Mutation.PutStock(WIDGET, 0, 5, 0, 1), new Mutation.PutStock(WIDGET, 2, 5, 0, 2)),
                expired.mutations().stream().filter(Mutation.PutStock.class::isInstance).toList());
    }

    @Test
    @DisplayName("a delivery goes to the shards with the least available first, levelling them")
    void deliveriesLevel() {
        Snapshot snapshot = Fixtures.snapshot()
                .shard(new StockShard(WIDGET, 0, 5, 0, 0))
                .shard(new StockShard(WIDGET, 1, 2, 2, 0))
                .shard(new StockShard(WIDGET, 2, 4, 1, 0))
                .shard(new StockShard(WIDGET, 3, 0, 0, 0))
                .build();

        Decision decision = Kernel.decide(snapshot, new Command.Adjust(key("delivery"), WIDGET, 6), T0);

        // Available 5, 0, 3, 0: the two empty shards rise to 3, which costs the six.
        assertEquals(
                List.of(new Mutation.PutStock(WIDGET, 1, 5, 2, 0), new Mutation.PutStock(WIDGET, 3, 3, 0, 0)),
                decision.mutations());
        assertEquals(new Outcome.Adjusted(WIDGET, 17, 3), decision.outcome(), "the SKU's level, added up");
        Event.StockAdjusted event = Fixtures.onlyEvent(decision, Event.StockAdjusted.class);
        assertEquals(17, event.onHand());
    }

    @Test
    @DisplayName("an odd delivery gives its last units to the lowest-numbered shards at the level")
    void deliveriesThatDoNotDivide() {
        Snapshot snapshot = sharded(0, 0, 0);

        Decision decision = Kernel.decide(snapshot, new Command.Adjust(key("delivery"), WIDGET, 5), T0);

        assertEquals(
                List.of(new Mutation.PutStock(WIDGET, 0, 2, 0, 0), new Mutation.PutStock(WIDGET, 1, 2, 0, 0),
                        new Mutation.PutStock(WIDGET, 2, 1, 0, 0)),
                decision.mutations());
    }

    @Test
    @DisplayName("a write-off takes from the shards with the most available first, and never a reserved unit")
    void writeOffsLevel() {
        Snapshot snapshot = Fixtures.snapshot()
                .shard(new StockShard(WIDGET, 0, 5, 0, 0))
                .shard(new StockShard(WIDGET, 1, 2, 2, 0))
                .shard(new StockShard(WIDGET, 2, 4, 1, 0))
                .shard(new StockShard(WIDGET, 3, 0, 0, 0))
                .build();

        Decision decision = Kernel.decide(snapshot, new Command.Adjust(key("breakage"), WIDGET, -4), T0);

        // Available 5, 0, 3, 0: the two fullest come down to 2.
        assertEquals(
                List.of(new Mutation.PutStock(WIDGET, 0, 2, 0, 0), new Mutation.PutStock(WIDGET, 2, 3, 1, 0)),
                decision.mutations());

        Decision everything = Kernel.decide(snapshot, new Command.Adjust(key("all"), WIDGET, -8), T0);
        assertEquals(new Outcome.Adjusted(WIDGET, 3, 3), everything.outcome(), "all eight available, and not the three reserved");
        Decision tooMuch = Kernel.decide(snapshot, new Command.Adjust(key("more"), WIDGET, -9), T0);
        assertEquals(RejectionCode.INSUFFICIENT_STOCK, Fixtures.rejection(tooMuch).code());
    }

    @Test
    @DisplayName("a snapshot with a gap in a SKU's shards is not a snapshot of that SKU")
    void gapsAreRefused() {
        assertThrows(
                IncompleteSnapshotException.class,
                () -> Fixtures.snapshot()
                        .shard(new StockShard(WIDGET, 0, 5, 0, 0))
                        .shard(new StockShard(WIDGET, 2, 5, 0, 0))
                        .build());
    }

    @Test
    @DisplayName("a hold's allocations must add up to its lines, from shards named once")
    void allocationsAddUp() {
        assertThrows(IllegalArgumentException.class, () -> holding("r", new Allocation(sku("gadget"), 0, 3)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Reservation(rid("r"), key("k"), List.of(line("widget", 3)), List.of(new Allocation(WIDGET, 0, 2)),
                        ReservationState.HELD, T0, T0.plus(TTL), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> holding("r", new Allocation(WIDGET, 1, 1), new Allocation(WIDGET, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> new Command.Shard(key("k"), WIDGET, 0));
        assertThrows(IllegalArgumentException.class, () -> new Command.Shard(key("k"), WIDGET, Command.MAX_SHARDS + 1));
    }

    // --- helpers ------------------------------------------------------------------------------

    /** A SKU in as many shards as there are numbers, each with that many available. */
    private static Snapshot sharded(long... available) {
        Snapshot.Builder builder = Fixtures.snapshot();
        for (int i = 0; i < available.length; i++) {
            builder.shard(new StockShard(WIDGET, i, available[i], 0, 0));
        }
        return builder.build();
    }

    private static Decision reserve(Snapshot snapshot, String id, long quantity) {
        return Kernel.decide(
                snapshot, new Command.Reserve(key("k-" + id), rid(id), List.of(line("widget", quantity)), TTL), T0);
    }

    /** An id whose holds start at {@code shard} of four, found by asking the kernel rather than by hashing here. */
    private static String idStartingAt(int shard) {
        Snapshot plenty = sharded(9, 9, 9, 9);
        for (int i = 0; i < 1_000; i++) {
            String id = "r-" + i;
            Reservation held = Fixtures.only(reserve(plenty, id, 1), Mutation.InsertReservation.class).reservation();
            if (held.allocations().get(0).shard() == shard) {
                return id;
            }
        }
        throw new AssertionError("no id in a thousand starts at shard " + shard);
    }

    private static Reservation holding(String id, Allocation... allocations) {
        long units = 0;
        for (Allocation allocation : allocations) {
            units += allocation.quantity();
        }
        return new Reservation(rid(id), key("k-" + id), List.of(line("widget", units)), List.of(allocations),
                ReservationState.HELD, T0, T0.plus(TTL), 0);
    }
}
